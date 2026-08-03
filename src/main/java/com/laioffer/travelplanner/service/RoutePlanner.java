package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Orders the POIs of a single day and turns that order into a clock timeline.
 *
 * <p>Ordering is an open-path Travelling Salesman Problem <em>with time windows</em>. Minimizing raw
 * travel time is not enough: it happily sends you to a bar that opens at 18:00 first thing in the
 * morning, and to a museum after it has closed. So the objective is lexicographic —
 * first minimize minutes spent inside a closed venue, then minimize the time the day ends, which
 * counts travel and waiting together.
 *
 * <p>For the day sizes a human actually plans (n &le; 12) we solve it <em>exactly</em> with Held-Karp
 * bitmask DP over (visited set, last stop) in O(n^2 * 2^n): the DP value is the earliest achievable
 * departure clock, and arriving earlier is never worse because waiting is always allowed. Above that
 * we fall back to earliest-completion greedy plus 2-opt, which lands within a few percent in
 * milliseconds.
 */
@Service
public class RoutePlanner {

    /** Above this many stops per day, exact DP is replaced by the heuristic. */
    private static final int EXACT_LIMIT = 12;

    private final RouteProvider routes;

    public RoutePlanner(RouteProvider routes) {
        this.routes = routes;
    }

    // ------------------------------------------------------------------ ordering

    /**
     * @param lockFirst keep the current first stop as the day's starting point (hotel, breakfast…)
     * @return indices into {@code pois}, in the recommended visiting order
     */
    public List<Integer> optimizeOrder(List<Poi> pois, TravelMode mode, int dayStartHour, boolean lockFirst) {
        int n = pois.size();
        if (n <= 2) {
            return identity(n);
        }
        int[][] cost = routes.matrix(pois, mode);
        int dayStart = dayStartHour * 60;
        int[] order = n <= EXACT_LIMIT
                ? heldKarp(pois, cost, dayStart, lockFirst)
                : twoOpt(greedyEarliestFinish(pois, cost, dayStart, lockFirst), pois, cost, dayStart, lockFirst);
        List<Integer> result = new ArrayList<>(n);
        for (int i : order) {
            result.add(i);
        }
        return result;
    }

    // --- the two ingredients every candidate order is judged on ---

    private static int openMinutes(Poi poi) {
        return poi.isAlwaysOpen() ? 0 : poi.getOpenHour() * 60;
    }

    private static int closeMinutes(Poi poi) {
        return poi.isAlwaysOpen() ? Integer.MAX_VALUE / 4 : poi.getCloseHour() * 60;
    }

    /** Departure clock after visiting {@code poi}, respecting its opening time. */
    private static int visitEnd(Poi poi, int arrive) {
        return Math.max(arrive, openMinutes(poi)) + poi.getAvgVisitMinutes();
    }

    /** Minutes of the visit that fall after closing time — what we refuse to trade away. */
    private static int overrun(Poi poi, int leave) {
        return Math.max(0, leave - closeMinutes(poi));
    }

    /**
     * Exact open-path TSP with time windows.
     *
     * <p>{@code clock[mask][last]} is the earliest we can be done with {@code last} having visited
     * exactly {@code mask}; {@code overrun[mask][last]} is the closed-venue time accumulated on the
     * way. States are compared on (overrun, clock), so a schedule that respects closing times always
     * beats a marginally shorter one that does not.
     */
    private int[] heldKarp(List<Poi> pois, int[][] cost, int dayStart, boolean lockFirst) {
        int n = pois.size();
        final int full = (1 << n) - 1;
        final int inf = Integer.MAX_VALUE / 4;
        int[][] clock = new int[1 << n][n];
        int[][] overrun = new int[1 << n][n];
        int[][] parent = new int[1 << n][n];
        for (int i = 0; i < clock.length; i++) {
            Arrays.fill(clock[i], inf);
            Arrays.fill(overrun[i], inf);
            Arrays.fill(parent[i], -1);
        }

        for (int i = 0; i < n; i++) {
            if (lockFirst && i != 0) {
                continue;
            }
            Poi poi = pois.get(i);
            int leave = visitEnd(poi, dayStart);
            clock[1 << i][i] = leave;
            overrun[1 << i][i] = overrun(poi, leave);
        }

        for (int mask = 1; mask <= full; mask++) {
            for (int last = 0; last < n; last++) {
                if ((mask & (1 << last)) == 0 || clock[mask][last] >= inf) {
                    continue;
                }
                for (int next = 0; next < n; next++) {
                    if ((mask & (1 << next)) != 0) {
                        continue;
                    }
                    Poi poi = pois.get(next);
                    int leave = visitEnd(poi, clock[mask][last] + cost[last][next]);
                    int over = overrun[mask][last] + overrun(poi, leave);
                    int nextMask = mask | (1 << next);
                    if (better(over, leave, overrun[nextMask][next], clock[nextMask][next])) {
                        clock[nextMask][next] = leave;
                        overrun[nextMask][next] = over;
                        parent[nextMask][next] = last;
                    }
                }
            }
        }

        int bestEnd = -1;
        for (int i = 0; i < n; i++) {
            if (clock[full][i] < inf
                    && (bestEnd < 0 || better(overrun[full][i], clock[full][i], overrun[full][bestEnd], clock[full][bestEnd]))) {
                bestEnd = i;
            }
        }

        int[] order = new int[n];
        int mask = full;
        int cur = bestEnd;
        for (int pos = n - 1; pos >= 0; pos--) {
            order[pos] = cur;
            int prev = parent[mask][cur];
            mask ^= (1 << cur);
            cur = prev;
        }
        return order;
    }

    private static boolean better(int overrunA, int clockA, int overrunB, int clockB) {
        return overrunA != overrunB ? overrunA < overrunB : clockA < clockB;
    }

    /** Greedy construction: at every step take the stop we can be finished with soonest. */
    private int[] greedyEarliestFinish(List<Poi> pois, int[][] cost, int dayStart, boolean lockFirst) {
        int n = pois.size();
        boolean[] used = new boolean[n];
        int[] order = new int[n];
        int start = 0;
        if (!lockFirst) {
            int bestLeave = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int leave = visitEnd(pois.get(i), dayStart);
                if (leave < bestLeave) {
                    bestLeave = leave;
                    start = i;
                }
            }
        }
        order[0] = start;
        used[start] = true;
        int clock = visitEnd(pois.get(start), dayStart);
        for (int pos = 1; pos < n; pos++) {
            int from = order[pos - 1];
            int bestNext = -1;
            int bestLeave = Integer.MAX_VALUE;
            int bestOverrun = Integer.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (used[j]) {
                    continue;
                }
                int leave = visitEnd(pois.get(j), clock + cost[from][j]);
                int over = overrun(pois.get(j), leave);
                if (bestNext < 0 || better(over, leave, bestOverrun, bestLeave)) {
                    bestNext = j;
                    bestLeave = leave;
                    bestOverrun = over;
                }
            }
            order[pos] = bestNext;
            used[bestNext] = true;
            clock = bestLeave;
        }
        return order;
    }

    /** Segment-reversal improvement, scored on the full day schedule rather than distance alone. */
    private int[] twoOpt(int[] order, List<Poi> pois, int[][] cost, int dayStart, boolean lockFirst) {
        int n = order.length;
        int from = lockFirst ? 1 : 0;
        long best = score(order, pois, cost, dayStart);
        boolean improved = true;
        int guard = 0;
        while (improved && guard++ < 100) {
            improved = false;
            for (int i = from; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    reverse(order, i, j);
                    long candidate = score(order, pois, cost, dayStart);
                    if (candidate < best) {
                        best = candidate;
                        improved = true;
                    } else {
                        reverse(order, i, j);
                    }
                }
            }
        }
        return order;
    }

    /** Lexicographic (overrun, end clock) folded into one comparable number. */
    private long score(int[] order, List<Poi> pois, int[][] cost, int dayStart) {
        int clock = dayStart;
        int over = 0;
        for (int pos = 0; pos < order.length; pos++) {
            if (pos > 0) {
                clock += cost[order[pos - 1]][order[pos]];
            }
            Poi poi = pois.get(order[pos]);
            clock = visitEnd(poi, clock);
            over += overrun(poi, clock);
        }
        return over * 100_000L + clock;
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
