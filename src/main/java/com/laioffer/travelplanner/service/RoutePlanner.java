package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Orders the POIs of a single day and turns that order into a clock timeline.
 *
 * <p>Ordering is an open-path Travelling Salesman Problem <em>with time windows</em>. Minimizing raw
 * travel time is not enough: it happily sends you to a bar that opens at 18:00 first thing in the
 * morning, and to a museum after it has closed. So the objective is lexicographic —
 * first minimize minutes spent inside a closed venue, then minimize the time the day ends, which
 * counts travel and waiting together.
 *
 * <p>For at most 12 movable stops we solve the soft-time-window objective exactly with a multi-label
 * Held-Karp search. Each (visited movable set, last stop) state keeps the non-dominated
 * (closed minutes, departure clock) labels; keeping only one label is not exact because a slightly
 * worse partial violation can leave enough time to avoid a much larger violation later. Locked stops
 * remain in the full route and are forced into their original slots. Larger days use a deterministic
 * earliest-completion seed followed by 2-opt, with the user's current order retained as a no-worse
 * baseline.
 */
@Service
public class RoutePlanner {

    /** Above this many stops per day, exact DP is replaced by the heuristic. */
    static final int EXACT_LIMIT = 12;

    enum Algorithm {
        HELD_KARP,
        GREEDY_TWO_OPT
    }

    private final RouteProvider routes;

    public RoutePlanner(RouteProvider routes) {
        this.routes = routes;
    }

    // ------------------------------------------------------------------ ordering

    /** Compatibility overload for callers that only pin the first stop. */
    public List<Integer> optimizeOrder(List<Poi> pois, TravelMode mode, int dayStartHour, boolean lockFirst) {
        List<Boolean> locked = new ArrayList<>(pois.size());
        for (int i = 0; i < pois.size(); i++) {
            locked.add(lockFirst && i == 0);
        }
        return optimizeOrder(pois, mode, dayStartHour, locked);
    }

    /**
     * Orders a complete day while forcing every locked POI to remain in its original slot.
     *
     * @param lockedPositions one flag per POI; a true value pins that POI to the same list index
     * @return indices into {@code pois}, in the recommended visiting order
     */
    public List<Integer> optimizeOrder(List<Poi> pois, TravelMode mode, int dayStartHour,
                                       List<Boolean> lockedPositions) {
        int n = pois.size();
        if (lockedPositions.size() != n) {
            throw new IllegalArgumentException("lockedPositions must contain one flag per POI");
        }
        if (n <= 1) {
            return identity(n);
        }

        boolean[] locked = new boolean[n];
        int movableCount = 0;
        for (int i = 0; i < n; i++) {
            locked[i] = Boolean.TRUE.equals(lockedPositions.get(i));
            if (!locked[i]) {
                movableCount++;
            }
        }
        if (movableCount <= 1) {
            return identity(n);
        }

        int[][] cost = routes.matrix(pois, mode);
        validateMatrix(cost, n);
        int dayStart = Math.multiplyExact(dayStartHour, 60);
        int[] order;
        if (algorithmFor(movableCount) == Algorithm.HELD_KARP) {
            order = heldKarp(pois, cost, dayStart, locked, movableCount);
        } else {
            int[] original = identityArray(n);
            int[] greedy = greedyEarliestFinish(pois, cost, dayStart, locked);
            int[] seed = compareOrders(greedy, original, pois, cost, dayStart) < 0 ? greedy : original;
            order = twoOpt(seed, pois, cost, dayStart, locked);
        }
        List<Integer> result = new ArrayList<>(n);
        for (int i : order) {
            result.add(i);
        }
        return result;
    }

    static Algorithm algorithmFor(int movableStops) {
        return movableStops <= EXACT_LIMIT ? Algorithm.HELD_KARP : Algorithm.GREEDY_TWO_OPT;
    }

    // --- the two ingredients every candidate order is judged on ---

    private record Visit(int leaveMinutes, int closedMinutes) {
    }

    private record Objective(int closedMinutes, int endMinutes) {
    }

    private record StateKey(int mask, int last) {
    }

    private record Label(int mask, int last, int clock, int closedMinutes, int[] path) {
    }

    private static int openMinutes(Poi poi) {
        return poi.isAlwaysOpen() ? 0 : poi.getOpenHour() * 60;
    }

    private static int closeMinutes(Poi poi) {
        return poi.isAlwaysOpen() ? Integer.MAX_VALUE / 4 : poi.getCloseHour() * 60;
    }

    /** The overlap between the actual visit interval and the time after the venue closes. */
    private static Visit visit(Poi poi, int arrive) {
        int start = Math.max(arrive, openMinutes(poi));
        int leave = Math.addExact(start, poi.getAvgVisitMinutes());
        int closed = poi.isAlwaysOpen() ? 0 : Math.max(0, leave - Math.max(start, closeMinutes(poi)));
        return new Visit(leave, closed);
    }

    /**
     * Exact open-path TSP with time windows.
     *
     * <p>The bitmask contains movable POIs only. Full route positions are still processed in order, so
     * locked POIs contribute travel, visit duration and opening-hour effects while being forced into
     * their original slots. A state keeps every non-dominated (closed minutes, clock) label.
     */
    private int[] heldKarp(List<Poi> pois, int[][] cost, int dayStart,
                           boolean[] locked, int movableCount) {
        int n = pois.size();
        int[] movableBit = new int[n];
        Arrays.fill(movableBit, -1);
        int bit = 0;
        for (int i = 0; i < n; i++) {
            if (!locked[i]) {
                movableBit[i] = bit++;
            }
        }

        Map<StateKey, List<Label>> states = new HashMap<>();
        Label initial = new Label(0, -1, dayStart, 0, new int[0]);
        states.put(new StateKey(0, -1), new ArrayList<>(List.of(initial)));

        for (int position = 0; position < n; position++) {
            Map<StateKey, List<Label>> nextStates = new HashMap<>();
            for (List<Label> labels : states.values()) {
                for (Label label : labels) {
                    if (locked[position]) {
                        addTransition(nextStates, label, position, label.mask(), pois, cost);
                        continue;
                    }
                    for (int candidate = 0; candidate < n; candidate++) {
                        int candidateBit = movableBit[candidate];
                        if (candidateBit < 0 || (label.mask() & (1 << candidateBit)) != 0) {
                            continue;
                        }
                        addTransition(nextStates, label, candidate,
                                label.mask() | (1 << candidateBit), pois, cost);
                    }
                }
            }
            states = nextStates;
        }

        int fullMask = (1 << movableCount) - 1;
        Label best = null;
        for (Map.Entry<StateKey, List<Label>> entry : states.entrySet()) {
            if (entry.getKey().mask() != fullMask) {
                continue;
            }
            for (Label label : entry.getValue()) {
                if (best == null || compareLabels(label, best) < 0) {
                    best = label;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("Exact route search produced no complete order");
        }
        return best.path();
    }

    private void addTransition(Map<StateKey, List<Label>> states, Label previous, int next,
                               int nextMask, List<Poi> pois, int[][] cost) {
        int arrive = previous.clock();
        if (previous.last() >= 0) {
            arrive = Math.addExact(arrive, cost[previous.last()][next]);
        }
        Visit visit = visit(pois.get(next), arrive);
        Label candidate = new Label(nextMask, next, visit.leaveMinutes(),
                previous.closedMinutes() + visit.closedMinutes(), append(previous.path(), next));
        addPareto(states, candidate);
    }

    private void addPareto(Map<StateKey, List<Label>> states, Label candidate) {
        StateKey key = new StateKey(candidate.mask(), candidate.last());
        List<Label> labels = states.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (Iterator<Label> iterator = labels.iterator(); iterator.hasNext(); ) {
            Label existing = iterator.next();
            if (sameObjective(existing, candidate)) {
                if (comparePaths(existing.path(), candidate.path()) <= 0) {
                    return;
                }
                iterator.remove();
            } else if (dominates(existing, candidate)) {
                return;
            } else if (dominates(candidate, existing)) {
                iterator.remove();
            }
        }
        labels.add(candidate);
    }

    private static boolean sameObjective(Label a, Label b) {
        return a.closedMinutes() == b.closedMinutes() && a.clock() == b.clock();
    }

    private static boolean dominates(Label a, Label b) {
        return a.closedMinutes() <= b.closedMinutes() && a.clock() <= b.clock();
    }

    private static int compareLabels(Label a, Label b) {
        int objective = compareObjectives(
                new Objective(a.closedMinutes(), a.clock()),
                new Objective(b.closedMinutes(), b.clock()));
        return objective != 0 ? objective : comparePaths(a.path(), b.path());
    }

    /** Greedy construction: at every step take the stop we can be finished with soonest. */
    private int[] greedyEarliestFinish(List<Poi> pois, int[][] cost, int dayStart, boolean[] locked) {
        int n = pois.size();
        boolean[] used = new boolean[n];
        int[] order = new int[n];
        int clock = dayStart;
        int previous = -1;
        for (int pos = 0; pos < n; pos++) {
            if (locked[pos]) {
                int next = pos;
                int arrive = previous < 0 ? clock : Math.addExact(clock, cost[previous][next]);
                Visit visit = visit(pois.get(next), arrive);
                order[pos] = next;
                used[next] = true;
                previous = next;
                clock = visit.leaveMinutes();
                continue;
            }
            int bestNext = -1;
            int bestLeave = Integer.MAX_VALUE;
            int bestClosed = Integer.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (locked[j] || used[j]) {
                    continue;
                }
                int arrive = previous < 0 ? clock : Math.addExact(clock, cost[previous][j]);
                Visit visit = visit(pois.get(j), arrive);
                if (bestNext < 0
                        || compareObjectives(new Objective(visit.closedMinutes(), visit.leaveMinutes()),
                        new Objective(bestClosed, bestLeave)) < 0
                        || (visit.closedMinutes() == bestClosed && visit.leaveMinutes() == bestLeave && j < bestNext)) {
                    bestNext = j;
                    bestLeave = visit.leaveMinutes();
                    bestClosed = visit.closedMinutes();
                }
            }
            order[pos] = bestNext;
            used[bestNext] = true;
            previous = bestNext;
            clock = bestLeave;
        }
        return order;
    }

    /** Segment-reversal improvement, scored on the full day schedule rather than distance alone. */
    private int[] twoOpt(int[] seed, List<Poi> pois, int[][] cost, int dayStart, boolean[] locked) {
        int[] movableOrder = new int[(int) java.util.stream.IntStream.range(0, locked.length)
                .filter(i -> !locked[i]).count()];
        int cursor = 0;
        for (int position = 0; position < seed.length; position++) {
            if (!locked[position]) {
                movableOrder[cursor++] = seed[position];
            }
        }

        int[] bestOrder = seed.clone();
        Objective best = score(bestOrder, pois, cost, dayStart);
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 100) {
            improved = false;
            for (int i = 0; i < movableOrder.length - 1; i++) {
                for (int j = i + 1; j < movableOrder.length; j++) {
                    reverse(movableOrder, i, j);
                    int[] candidateOrder = mergeLocked(movableOrder, locked);
                    Objective candidate = score(candidateOrder, pois, cost, dayStart);
                    int comparison = compareObjectives(candidate, best);
                    if (comparison < 0 || (comparison == 0 && comparePaths(candidateOrder, bestOrder) < 0)) {
                        best = candidate;
                        bestOrder = candidateOrder;
                        improved = true;
                    } else {
                        reverse(movableOrder, i, j);
                    }
                }
            }
        }
        return bestOrder;
    }

    private Objective score(int[] order, List<Poi> pois, int[][] cost, int dayStart) {
        int clock = dayStart;
        int closed = 0;
        for (int pos = 0; pos < order.length; pos++) {
            if (pos > 0) {
                clock = Math.addExact(clock, cost[order[pos - 1]][order[pos]]);
            }
            Visit visit = visit(pois.get(order[pos]), clock);
            clock = visit.leaveMinutes();
            closed += visit.closedMinutes();
        }
        return new Objective(closed, clock);
    }

    private int compareOrders(int[] a, int[] b, List<Poi> pois, int[][] cost, int dayStart) {
        int objective = compareObjectives(score(a, pois, cost, dayStart), score(b, pois, cost, dayStart));
        return objective != 0 ? objective : comparePaths(a, b);
    }

    private static int compareObjectives(Objective a, Objective b) {
        int closed = Integer.compare(a.closedMinutes(), b.closedMinutes());
        return closed != 0 ? closed : Integer.compare(a.endMinutes(), b.endMinutes());
    }

    private static int comparePaths(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int comparison = Integer.compare(a[i], b[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int[] append(int[] path, int value) {
        int[] result = Arrays.copyOf(path, path.length + 1);
        result[path.length] = value;
        return result;
    }

    private static void validateMatrix(int[][] cost, int size) {
        if (cost == null || cost.length != size) {
            throw new IllegalArgumentException("route matrix must be " + size + " x " + size);
        }
        for (int from = 0; from < size; from++) {
            if (cost[from] == null || cost[from].length != size) {
                throw new IllegalArgumentException("route matrix must be " + size + " x " + size);
            }
            for (int to = 0; to < size; to++) {
                if (cost[from][to] < 0) {
                    throw new IllegalArgumentException(
                            "route matrix contains a negative cost at [" + from + "][" + to + "]");
                }
                if (cost[from][to] == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "route matrix contains an unreachable edge at [" + from + "][" + to
                                    + "]; the provider must supply its deterministic fallback estimate");
                }
            }
        }
    }

    private static int[] mergeLocked(int[] movableOrder, boolean[] locked) {
        int[] fullOrder = new int[locked.length];
        int cursor = 0;
        for (int position = 0; position < locked.length; position++) {
            fullOrder[position] = locked[position] ? position : movableOrder[cursor++];
        }
        return fullOrder;
    }

    private void reverse(int[] order, int i, int j) {
        while (i < j) {
            int tmp = order[i];
            order[i++] = order[j];
            order[j--] = tmp;
        }
    }

    private List<Integer> identity(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(i);
        }
        return list;
    }

    private int[] identityArray(int n) {
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        return order;
    }

    // ------------------------------------------------------------------ timeline

    public record StopPlan(int arriveMinutes, int leaveMinutes, int travelMinutesFromPrev,
                           double travelKmFromPrev, String polylineFromPrev, List<NoticeDto> warnings) {
    }

    public record DayPlan(List<StopPlan> stops, int visitMinutes, int travelMinutes,
                          int startMinutes, int endMinutes, List<NoticeDto> warnings) {
    }

    /** Codes the client turns into sentences. Kept next to the logic that raises them. */
    public static final String OPENS_LATER = "warning.opensLater";
    public static final String CLOSES_EARLY = "warning.closesEarly";
    public static final String DAY_RUNS_LATE = "warning.dayRunsLate";
    public static final String TRAVEL_HEAVY = "warning.travelHeavy";

    /**
     * Walks the day in order and produces arrival/departure clock times, flagging the two things
     * that silently ruin a real trip: arriving before a place opens, and running past closing time.
     */
    public DayPlan buildDay(List<Poi> pois, TravelMode mode, int dayStartHour, int dayEndHour) {
        List<TravelLeg> legs = routes.legs(pois, mode);
        List<StopPlan> stops = new ArrayList<>();
        List<NoticeDto> dayWarnings = new ArrayList<>();
        int visitMinutes = 0;
        int travelMinutes = 0;
        int clock = dayStartHour * 60;

        for (int i = 0; i < pois.size(); i++) {
            Poi poi = pois.get(i);
            List<NoticeDto> warnings = new ArrayList<>();
            TravelLeg leg = i > 0 && i - 1 < legs.size() ? legs.get(i - 1) : TravelLeg.none();
            int legMinutes = leg.minutes();
            if (i > 0) {
                clock += legMinutes;
                travelMinutes += legMinutes;
            }

            int arrive = clock;
            if (!poi.isAlwaysOpen() && arrive < poi.getOpenHour() * 60) {
                int wait = poi.getOpenHour() * 60 - arrive;
                warnings.add(NoticeDto.of(OPENS_LATER,
                        "wait", wait, "opensAt", fmt(poi.getOpenHour() * 60)));
                arrive = poi.getOpenHour() * 60;
            }
            int leave = arrive + poi.getAvgVisitMinutes();
            if (!poi.isAlwaysOpen() && leave > poi.getCloseHour() * 60) {
                warnings.add(NoticeDto.of(CLOSES_EARLY, "closesAt", fmt(poi.getCloseHour() * 60)));
            }

            stops.add(new StopPlan(arrive, leave, legMinutes, leg.km(), leg.polyline(), warnings));
            visitMinutes += poi.getAvgVisitMinutes();
            clock = leave;
        }

        if (!pois.isEmpty() && clock > dayEndHour * 60) {
            dayWarnings.add(NoticeDto.of(DAY_RUNS_LATE, "endTime", fmt(clock)));
        }
        if (!pois.isEmpty() && travelMinutes > visitMinutes) {
            dayWarnings.add(NoticeDto.of(TRAVEL_HEAVY, "travelMinutes", travelMinutes));
        }

        return new DayPlan(stops, visitMinutes, travelMinutes, dayStartHour * 60, clock, dayWarnings);
    }

    public static String fmt(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h >= 24) {
            return String.format("%02d:%02d (+1d)", h - 24, m);
        }
        return String.format("%02d:%02d", h, m);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
