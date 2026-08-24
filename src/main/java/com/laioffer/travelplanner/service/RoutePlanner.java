package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Orders the POIs of a single day and turns that order into a clock timeline.
 *
 * <p>Ordering is an open-path Travelling Salesman Problem <em>with time windows</em>. Minimizing raw
 * travel time is not enough: it happily sends you to a bar that opens at 18:00 first thing in the
 * morning, and to a museum after it has closed. So the objective is lexicographic —
 * first minimize minutes spent inside a closed venue, then minimize the time the day ends, which
 * counts travel and waiting together. If both are tied, prefer less total travel time before using
 * the original-index path as a deterministic final tie-break.
 *
 * <p>For at most 12 movable stops we solve the soft-time-window objective exactly with a multi-label
 * Held-Karp search. Each (visited movable set, last stop) state keeps a tie-aware set of retained
 * labels; keeping only one label is not exact because a slightly worse partial violation can leave
 * enough time to avoid a much larger violation later. Locked stops remain in the full route and are
 * forced into their original slots. Larger days use a deterministic earliest-completion seed followed
 * by 2-opt, with the user's current order retained as a no-worse baseline.
 */
@Service
public class RoutePlanner {

    /** Above this many movable stops per day, exact DP is replaced by the heuristic. */
    static final int EXACT_LIMIT = 12;

    public enum Algorithm {
        FIXED_ORDER,
        HELD_KARP,
        GREEDY_TWO_OPT
    }

    public record RouteObjective(int closedMinutes, int finishMinutes, int travelMinutes) {
    }

    public record OptimizationMetrics(int movableStops, long algorithmNanos,
                                      long generatedLabels, long acceptedLabels, long prunedLabels,
                                      int maxFrontierSize, long peakFrontierLabelsInLayer) {
    }

    public record OptimizationResult(List<Integer> order, Algorithm algorithm, boolean optimal,
                                     RouteObjective before, RouteObjective after,
                                     OptimizationMetrics metrics) {
        public OptimizationResult {
            order = List.copyOf(order);
        }

        public boolean changed() {
            for (int position = 0; position < order.size(); position++) {
                if (order.get(position) != position) {
                    return true;
                }
            }
            return false;
        }
    }

    private final RouteProvider routes;
    private final LongSupplier nanoTime;

    @Autowired
    public RoutePlanner(RouteProvider routes) {
        this(routes, System::nanoTime);
    }

    RoutePlanner(RouteProvider routes, LongSupplier nanoTime) {
        this.routes = routes;
        this.nanoTime = nanoTime;
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
        return optimizeDetailed(pois, mode, dayStartHour, lockedPositions).order();
    }

    /**
     * Runs the optimizer and returns the evidence needed by Week 4 integration and benchmarks.
     * Provider/matrix time is deliberately excluded from {@code algorithmNanos}.
     */
    public OptimizationResult optimizeDetailed(List<Poi> pois, TravelMode mode, int dayStartHour,
                                               List<Boolean> lockedPositions) {
        int n = pois.size();
        if (lockedPositions.size() != n) {
            throw new IllegalArgumentException("lockedPositions must contain one flag per POI");
        }

        boolean[] locked = new boolean[n];
        int movableCount = 0;
        for (int i = 0; i < n; i++) {
            locked[i] = Boolean.TRUE.equals(lockedPositions.get(i));
            if (!locked[i]) {
                movableCount++;
            }
        }

        int[][] cost = n <= 1 ? new int[n][n] : routes.matrix(pois, mode);
        if (n > 1) {
            validateMatrix(cost, n);
        }
        int dayStart = Math.multiplyExact(dayStartHour, 60);
        int[] original = identityArray(n);

        long started = nanoTime.getAsLong();
        RouteObjective before = score(original, pois, cost, dayStart);
        Algorithm algorithm;
        boolean optimal;
        SearchOutcome outcome;
        if (movableCount <= 1) {
            algorithm = Algorithm.FIXED_ORDER;
            optimal = true;
            outcome = SearchOutcome.withoutLabels(original);
        } else if (algorithmFor(movableCount) == Algorithm.HELD_KARP) {
            algorithm = Algorithm.HELD_KARP;
            optimal = true;
            outcome = heldKarp(pois, cost, dayStart, locked, movableCount);
        } else {
            algorithm = Algorithm.GREEDY_TWO_OPT;
            optimal = false;
            int[] greedy = greedyEarliestFinish(pois, cost, dayStart, locked);
            int[] seed = compareOrders(greedy, original, pois, cost, dayStart) < 0 ? greedy : original;
            outcome = SearchOutcome.withoutLabels(twoOpt(seed, pois, cost, dayStart, locked));
        }
        RouteObjective after = score(outcome.order(), pois, cost, dayStart);
        long elapsed = Math.max(0L, nanoTime.getAsLong() - started);

        OptimizationMetrics metrics = new OptimizationMetrics(
                movableCount, elapsed,
                outcome.generatedLabels(), outcome.acceptedLabels(), outcome.prunedLabels(),
                outcome.maxFrontierSize(), outcome.peakFrontierLabelsInLayer());
        return new OptimizationResult(Arrays.stream(outcome.order()).boxed().toList(),
                algorithm, optimal, before, after, metrics);
    }

    static Algorithm algorithmFor(int movableStops) {
        return movableStops <= EXACT_LIMIT ? Algorithm.HELD_KARP : Algorithm.GREEDY_TWO_OPT;
    }

    // --- the two ingredients every candidate order is judged on ---

    private record Visit(int leaveMinutes, int closedMinutes) {
    }

    private record StateKey(int mask, int last) {
    }

    private record Label(int mask, int last, int clock, int closedMinutes, int travelMinutes,
                         Label parent, BigInteger tieCode) {
    }

    private record SearchOutcome(int[] order,
                                 long generatedLabels, long acceptedLabels, long prunedLabels,
                                 int maxFrontierSize, long peakFrontierLabelsInLayer) {
        private static SearchOutcome withoutLabels(int[] order) {
            return new SearchOutcome(order, 0, 0, 0, 0, 0);
        }
    }

    private static final class LabelMetricsAccumulator {
        private long generated;
        private long accepted;
        private long pruned;
        private int maxFrontierSize = 1;
        private long peakFrontierLabelsInLayer = 1;
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
     * their original slots. A state keeps the labels needed to preserve the documented objective,
     * including travel-time and deterministic ties that can become decisive after an opening wait.
     */
    private SearchOutcome heldKarp(List<Poi> pois, int[][] cost, int dayStart,
                                   boolean[] locked, int movableCount) {
        int n = pois.size();
        int tieBits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, n - 1)));
        int[] movableBit = new int[n];
        Arrays.fill(movableBit, -1);
        int bit = 0;
        for (int i = 0; i < n; i++) {
            if (!locked[i]) {
                movableBit[i] = bit++;
            }
        }

        LabelMetricsAccumulator metrics = new LabelMetricsAccumulator();
        Map<StateKey, List<Label>> states = new LinkedHashMap<>();
        Label initial = new Label(0, -1, dayStart, 0, 0, null, BigInteger.ZERO);
        states.put(new StateKey(0, -1), new ArrayList<>(List.of(initial)));

        for (int position = 0; position < n; position++) {
            Map<StateKey, List<Label>> nextStates = new LinkedHashMap<>();
            for (List<Label> labels : states.values()) {
                for (Label label : labels) {
                    if (locked[position]) {
                        addTransition(nextStates, label, position, label.mask(), pois, cost,
                                tieBits, metrics);
                        continue;
                    }
                    for (int candidate = 0; candidate < n; candidate++) {
                        int candidateBit = movableBit[candidate];
                        if (candidateBit < 0 || (label.mask() & (1 << candidateBit)) != 0) {
                            continue;
                        }
                        addTransition(nextStates, label, candidate,
                                label.mask() | (1 << candidateBit), pois, cost, tieBits, metrics);
                    }
                }
            }
            long retained = nextStates.values().stream().mapToLong(List::size).sum();
            metrics.peakFrontierLabelsInLayer = Math.max(metrics.peakFrontierLabelsInLayer, retained);
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
        return new SearchOutcome(reconstruct(best, n),
                metrics.generated, metrics.accepted, metrics.pruned,
                metrics.maxFrontierSize, metrics.peakFrontierLabelsInLayer);
    }

    private void addTransition(Map<StateKey, List<Label>> states, Label previous, int next,
                               int nextMask, List<Poi> pois, int[][] cost, int tieBits,
                               LabelMetricsAccumulator metrics) {
        int arrive = previous.clock();
        if (previous.last() >= 0) {
            arrive = Math.addExact(arrive, cost[previous.last()][next]);
        }
        Visit visit = visit(pois.get(next), arrive);
        metrics.generated++;
        int travel = previous.last() < 0
                ? previous.travelMinutes()
                : Math.addExact(previous.travelMinutes(), cost[previous.last()][next]);
        Label candidate = new Label(nextMask, next, visit.leaveMinutes(),
                Math.addExact(previous.closedMinutes(), visit.closedMinutes()), travel, previous,
                previous.tieCode().shiftLeft(tieBits).or(BigInteger.valueOf(next)));
        if (addPareto(states, candidate, metrics)) {
            metrics.accepted++;
        } else {
            metrics.pruned++;
        }
    }

    private boolean addPareto(Map<StateKey, List<Label>> states, Label candidate,
                              LabelMetricsAccumulator metrics) {
        StateKey key = new StateKey(candidate.mask(), candidate.last());
        List<Label> labels = states.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (Iterator<Label> iterator = labels.iterator(); iterator.hasNext(); ) {
            Label existing = iterator.next();
            if (sameObjective(existing, candidate)) {
                if (existing.tieCode().compareTo(candidate.tieCode()) <= 0) {
                    return false;
                }
                iterator.remove();
            } else if (dominates(existing, candidate)) {
                return false;
            } else if (dominates(candidate, existing)) {
                iterator.remove();
            }
        }
        labels.add(candidate);
        metrics.maxFrontierSize = Math.max(metrics.maxFrontierSize, labels.size());
        return true;
    }

    private static boolean sameObjective(Label a, Label b) {
        return a.closedMinutes() == b.closedMinutes()
                && a.clock() == b.clock()
                && a.travelMinutes() == b.travelMinutes();
    }

    private static boolean dominates(Label a, Label b) {
        if (a.closedMinutes() > b.closedMinutes() || a.clock() > b.clock()) {
            return false;
        }
        if (a.closedMinutes() < b.closedMinutes()) {
            return true;
        }
        // With equal primary score, an earlier prefix can later synchronize on an opening-time wait.
        // It may discard a later prefix only when it is also no worse on the remaining tie-breakers.
        if (a.travelMinutes() > b.travelMinutes()) {
            return false;
        }
        if (a.travelMinutes() < b.travelMinutes()) {
            return true;
        }
        return a.tieCode().compareTo(b.tieCode()) <= 0;
    }

    private static int compareLabels(Label a, Label b) {
        int objective = compareObjectives(
                new RouteObjective(a.closedMinutes(), a.clock(), a.travelMinutes()),
                new RouteObjective(b.closedMinutes(), b.clock(), b.travelMinutes()));
        return objective != 0 ? objective : a.tieCode().compareTo(b.tieCode());
    }

    private static int[] reconstruct(Label best, int size) {
        int[] order = new int[size];
        Label cursor = best;
        for (int position = size - 1; position >= 0; position--) {
            order[position] = cursor.last();
            cursor = cursor.parent();
        }
        return order;
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
            int bestTravel = Integer.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (locked[j] || used[j]) {
                    continue;
                }
                int arrive = previous < 0 ? clock : Math.addExact(clock, cost[previous][j]);
                Visit visit = visit(pois.get(j), arrive);
                int travel = previous < 0 ? 0 : cost[previous][j];
                if (bestNext < 0
                        || compareObjectives(new RouteObjective(
                                visit.closedMinutes(), visit.leaveMinutes(), travel),
                        new RouteObjective(bestClosed, bestLeave, bestTravel)) < 0
                        || (visit.closedMinutes() == bestClosed && visit.leaveMinutes() == bestLeave
                        && travel == bestTravel && j < bestNext)) {
                    bestNext = j;
                    bestLeave = visit.leaveMinutes();
                    bestClosed = visit.closedMinutes();
                    bestTravel = travel;
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
        RouteObjective best = score(bestOrder, pois, cost, dayStart);
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 100) {
            improved = false;
            for (int i = 0; i < movableOrder.length - 1; i++) {
                for (int j = i + 1; j < movableOrder.length; j++) {
                    reverse(movableOrder, i, j);
                    int[] candidateOrder = mergeLocked(movableOrder, locked);
                    RouteObjective candidate = score(candidateOrder, pois, cost, dayStart);
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

    private RouteObjective score(int[] order, List<Poi> pois, int[][] cost, int dayStart) {
        int clock = dayStart;
        int closed = 0;
        int travel = 0;
        for (int pos = 0; pos < order.length; pos++) {
            if (pos > 0) {
                int leg = cost[order[pos - 1]][order[pos]];
                clock = Math.addExact(clock, leg);
                travel = Math.addExact(travel, leg);
            }
            Visit visit = visit(pois.get(order[pos]), clock);
            clock = visit.leaveMinutes();
            closed += visit.closedMinutes();
        }
        return new RouteObjective(closed, clock, travel);
    }

    private int compareOrders(int[] a, int[] b, List<Poi> pois, int[][] cost, int dayStart) {
        int objective = compareObjectives(score(a, pois, cost, dayStart), score(b, pois, cost, dayStart));
        return objective != 0 ? objective : comparePaths(a, b);
    }

    private static int compareObjectives(RouteObjective a, RouteObjective b) {
        int closed = Integer.compare(a.closedMinutes(), b.closedMinutes());
        if (closed != 0) {
            return closed;
        }
        int finish = Integer.compare(a.finishMinutes(), b.finishMinutes());
        return finish != 0 ? finish : Integer.compare(a.travelMinutes(), b.travelMinutes());
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
