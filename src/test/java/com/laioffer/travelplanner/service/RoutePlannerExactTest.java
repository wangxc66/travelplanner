package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerExactTest {

    private static final int DAY_START_HOUR = 9;

    private final City city = new City("Test", "Test", "UTC", 0, 0, 12, "*");

    @Test
    void optimizesTwoStopsInsteadOfReturningTheInputOrder() {
        List<Poi> pois = List.of(
                poi("Late", 60, 18, 24),
                poi("Early", 60, 9, 10));
        int[][] cost = {
                {0, 10},
                {10, 0}
        };

        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        assertEquals(List.of(1, 0),
                planner.optimizeOrder(pois, TravelMode.WALK, DAY_START_HOUR, List.of(false, false)));
        assertEquals(List.of(0, 1),
                planner.optimizeOrder(pois, TravelMode.WALK, DAY_START_HOUR, List.of(true, false)));
    }

    @Test
    void reportsWeekFourResultMetadataAndExcludesProviderTime() {
        List<Poi> pois = List.of(
                poi("Late", 60, 18, 24),
                poi("Early", 60, 9, 10));
        int[][] cost = {
                {0, 10},
                {10, 0}
        };
        MatrixRouteProvider provider = new MatrixRouteProvider(cost);
        long[] ticks = {1_000L, 1_250L};
        AtomicInteger tick = new AtomicInteger();
        RoutePlanner planner = new RoutePlanner(provider, () -> ticks[tick.getAndIncrement()]);

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.WALK, DAY_START_HOUR, List.of(false, false));

        assertEquals(List.of(1, 0), result.order());
        assertEquals(RoutePlanner.Algorithm.HELD_KARP, result.algorithm());
        assertTrue(result.optimal());
        assertTrue(result.changed());
        assertEquals(new RoutePlanner.RouteObjective(60, 1210, 10), result.before());
        assertEquals(new RoutePlanner.RouteObjective(0, 1140, 10), result.after());
        assertEquals(250L, result.metrics().algorithmNanos());
        assertEquals(2, result.metrics().movableStops());
        assertTrue(result.metrics().generatedLabels() >= result.metrics().acceptedLabels());
        assertTrue(result.metrics().acceptedLabels() > 0);
        assertTrue(result.metrics().maxFrontierSize() >= 1);
        assertTrue(result.metrics().peakFrontierLabelsInLayer() >= 1);
        assertEquals(1, provider.matrixCalls,
                "before/after objectives must reuse the solver's one matrix snapshot");
    }

    @Test
    void keepsParetoLabelsNeededForTheGlobalTimeWindowOptimum() {
        List<Poi> pois = List.of(
                poi("A", 90, 9, 12),
                poi("B", 90, 10, 13),
                poi("C", 90, 11, 17),
                poi("D", 60, 12, 17),
                poi("E", 30, 9, 14));
        int[][] cost = {
                {0, 45, 20, 5, 5},
                {40, 0, 20, 30, 30},
                {5, 45, 0, 30, 10},
                {40, 30, 30, 0, 10},
                {15, 40, 45, 45, 0}
        };
        List<Boolean> locked = List.of(false, false, false, false, false);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        List<Integer> expected = bruteForceBest(pois, cost, locked);
        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.TRANSIT, DAY_START_HOUR, locked);
        List<Integer> actual = result.order();

        assertEquals(List.of(0, 4, 1, 2, 3), expected,
                "fixture must retain the known unique optimum");
        assertEquals(expected, actual);
        assertTrue(result.metrics().maxFrontierSize() >= 2,
                "the Pareto counterexample must retain multiple labels in at least one state");
    }

    @Test
    void keepsLexicographicallySmallerPrefixWhenOpeningWaitCanSynchronizeClocks() {
        List<Poi> pois = List.of(
                poi("A", 15, 10, 24),
                poi("B", 15, 0, 24),
                poi("C", 15, 0, 24),
                poi("Late", 15, 12, 24));
        int[][] cost = equalCostMatrix(4, 10);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.WALK, DAY_START_HOUR,
                List.of(false, false, true, true));

        assertEquals(List.of(0, 1, 2, 3), result.order(),
                "equal travel prefixes synchronize at Late, so the lexicographically smaller path wins");
        assertEquals(new RoutePlanner.RouteObjective(0, 735, 30), result.after());
    }

    @Test
    void prefersShorterTravelWhenClosedMinutesAndFinishTimeTie() {
        List<Poi> pois = List.of(
                poi("Start", 15, 0, 24),
                poi("Long-way", 15, 0, 24),
                poi("Short-way", 15, 0, 24),
                poi("Late", 15, 12, 24));
        int[][] cost = equalCostMatrix(4, 100);
        cost[0][1] = 50;
        cost[1][2] = 50;
        cost[2][3] = 10;
        cost[0][2] = 10;
        cost[2][1] = 10;
        cost[1][3] = 10;
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.TRANSIT, DAY_START_HOUR,
                List.of(true, false, false, true));

        assertEquals(new RoutePlanner.RouteObjective(0, 735, 110), result.before());
        assertEquals(new RoutePlanner.RouteObjective(0, 735, 30), result.after());
        assertEquals(List.of(0, 2, 1, 3), result.order(),
                "when closing impact and finish tie, the route with less travel must win");
    }

    @Test
    void lockedFirstStopStillAllowsTheFirstMovableStopToChange() {
        List<Poi> pois = List.of(
                poi("Hotel", 15, 0, 24),
                poi("Far", 15, 0, 24),
                poi("Near", 15, 0, 24),
                poi("Mid", 15, 0, 24));
        int[][] cost = {
                {0, 100, 1, 2},
                {1, 0, 1, 1},
                {1, 1, 0, 1},
                {1, 1, 1, 0}
        };
        MatrixRouteProvider provider = new MatrixRouteProvider(cost);
        RoutePlanner planner = new RoutePlanner(provider);

        List<Integer> actual = planner.optimizeOrder(
                pois, TravelMode.DRIVE, DAY_START_HOUR, List.of(true, false, false, false));

        assertEquals(List.of(0, 2, 1, 3), actual);
        assertEquals(4, provider.lastMatrixSize,
                "the locked hotel must remain in the optimizer's full matrix");
    }

    @Test
    void preservesMultipleLockedSlotsWhileOptimizingAllOtherStops() {
        List<Poi> pois = List.of(
                poi("A", 30, 9, 18),
                poi("Locked-B", 45, 9, 13),
                poi("C", 60, 10, 18),
                poi("Locked-D", 30, 12, 20),
                poi("E", 45, 9, 17));
        int[][] cost = {
                {0, 20, 40, 10, 30},
                {15, 0, 5, 35, 20},
                {30, 10, 0, 15, 5},
                {10, 25, 20, 0, 30},
                {25, 20, 5, 10, 0}
        };
        List<Boolean> locked = List.of(false, true, false, true, false);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        List<Integer> expected = bruteForceBest(pois, cost, locked);
        List<Integer> actual = planner.optimizeOrder(
                pois, TravelMode.WALK, DAY_START_HOUR, locked);

        assertEquals(expected, actual);
        assertEquals(1, actual.get(1));
        assertEquals(3, actual.get(3));
    }

    @Test
    void resolvesTiesDeterministically() {
        List<Poi> pois = List.of(
                poi("A", 30, 0, 24),
                poi("B", 30, 0, 24),
                poi("C", 30, 0, 24),
                poi("D", 30, 0, 24));
        int[][] cost = equalCostMatrix(4, 10);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));
        List<Boolean> unlocked = List.of(false, false, false, false);

        List<Integer> baseline = planner.optimizeOrder(
                pois, TravelMode.WALK, DAY_START_HOUR, unlocked);
        assertEquals(List.of(0, 1, 2, 3), baseline);
        for (int run = 0; run < 25; run++) {
            assertEquals(baseline,
                    planner.optimizeOrder(pois, TravelMode.WALK, DAY_START_HOUR, unlocked));
        }
    }

    @Test
    void usesExactSolverThroughTwelveMovableStops() {
        assertEquals(RoutePlanner.Algorithm.HELD_KARP, RoutePlanner.algorithmFor(12));
        assertEquals(RoutePlanner.Algorithm.GREEDY_TWO_OPT, RoutePlanner.algorithmFor(13));
    }

    @Test
    void runsDeterministicHeuristicForThirteenMovableStops() {
        int size = 13;
        List<Poi> pois = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pois.add(poi("P" + i, 15, 0, 24));
        }
        int[][] cost = equalCostMatrix(size, 100);
        cost[0][12] = 1;
        for (int from = 12; from >= 2; from--) {
            cost[from][from - 1] = 1;
        }
        List<Integer> expected = List.of(0, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1);
        List<Boolean> unlocked = Collections.nCopies(size, false);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(cost));

        RoutePlanner.OptimizationResult first = planner.optimizeDetailed(
                pois, TravelMode.DRIVE, DAY_START_HOUR, unlocked);
        RoutePlanner.OptimizationResult second = planner.optimizeDetailed(
                pois, TravelMode.DRIVE, DAY_START_HOUR, unlocked);

        assertEquals(expected, first.order());
        assertEquals(expected, second.order());
        assertEquals(RoutePlanner.Algorithm.GREEDY_TWO_OPT, first.algorithm());
        assertFalse(first.optimal());
        assertTrue(first.changed());
        assertEquals(new RoutePlanner.RouteObjective(0, 1935, 1200), first.before());
        assertEquals(new RoutePlanner.RouteObjective(0, 747, 12), first.after());
        assertEquals(first.before(), second.before());
        assertEquals(first.after(), second.after());
        assertEquals(13, first.metrics().movableStops());
        assertEquals(0, first.metrics().generatedLabels());
    }

    @Test
    void usesExactSolverForThirteenTotalStopsWhenOneIsLocked() {
        int size = 13;
        List<Poi> pois = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pois.add(poi("P" + i, 15, 0, 24));
        }
        List<Boolean> locked = new ArrayList<>(Collections.nCopies(size, false));
        locked.set(6, true);
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(equalCostMatrix(size, 10)));

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.TRANSIT, DAY_START_HOUR, locked);

        assertEquals(RoutePlanner.Algorithm.HELD_KARP, result.algorithm());
        assertTrue(result.optimal());
        assertEquals(12, result.metrics().movableStops());
        assertEquals(6, result.order().get(6));
        assertEquals(java.util.stream.IntStream.range(0, size).boxed().toList(), result.order());
    }

    @Test
    void reportsFixedOrderWhenOnlyOneStopCanMove() {
        List<Poi> pois = List.of(
                poi("Locked-A", 30, 0, 24),
                poi("Movable", 30, 0, 24),
                poi("Locked-C", 30, 0, 24));
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(equalCostMatrix(3, 10)));

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.WALK, DAY_START_HOUR, List.of(true, false, true));

        assertEquals(RoutePlanner.Algorithm.FIXED_ORDER, result.algorithm());
        assertTrue(result.optimal());
        assertFalse(result.changed());
        assertEquals(result.before(), result.after());
        assertEquals(1, result.metrics().movableStops());
        assertEquals(0, result.metrics().generatedLabels());
    }

    @Test
    void matchesBruteForceAcrossDeterministicSmallCasesWithLocks() {
        Random random = new Random(20260816L);
        for (int fixture = 0; fixture < 25; fixture++) {
            int fixtureId = fixture;
            int size = 6;
            List<Poi> pois = java.util.stream.IntStream.range(0, size)
                    .mapToObj(i -> {
                        int open = 8 + random.nextInt(5);
                        int close = Math.min(24, open + 2 + random.nextInt(7));
                        int visit = 15 * (1 + random.nextInt(4));
                        return poi("F" + fixtureId + "-P" + i, visit, open, close);
                    })
                    .toList();
            int[][] matrix = new int[size][size];
            for (int from = 0; from < size; from++) {
                for (int to = 0; to < size; to++) {
                    matrix[from][to] = from == to ? 0 : 5 + random.nextInt(56);
                }
            }
            List<Boolean> locked = java.util.stream.IntStream.range(0, size)
                    .mapToObj(i -> i >= 2 && random.nextInt(5) == 0)
                    .toList();

            List<Integer> expected = bruteForceBest(pois, matrix, locked);
            List<Integer> actual = new RoutePlanner(new MatrixRouteProvider(matrix))
                    .optimizeOrder(pois, TravelMode.WALK, DAY_START_HOUR, locked);

            assertEquals(expected, actual, "brute-force mismatch for fixture " + fixture);
        }
    }

    @Test
    void rejectsMismatchedLockFlags() {
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(equalCostMatrix(2, 1)));
        List<Poi> pois = List.of(poi("A", 30, 0, 24), poi("B", 30, 0, 24));

        assertThrows(IllegalArgumentException.class,
                () -> planner.optimizeOrder(pois, TravelMode.WALK, DAY_START_HOUR, List.of(false)));
    }

    @Test
    void rejectsUnreachableEdgesBeforeClockArithmeticCanOverflow() {
        int[][] unreachable = {
                {0, Integer.MAX_VALUE},
                {10, 0}
        };
        RoutePlanner planner = new RoutePlanner(new MatrixRouteProvider(unreachable));
        List<Poi> pois = List.of(poi("A", 30, 0, 24), poi("B", 30, 0, 24));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> planner.optimizeOrder(
                        pois, TravelMode.WALK, DAY_START_HOUR, List.of(false, false)));
        assertTrue(error.getMessage().contains("unreachable edge"));
    }

    private Poi poi(String name, int visitMinutes, int openHour, int closeHour) {
        return new Poi(city, name, "Landmark", 0, 0, 4.5,
                visitMinutes, openHour, closeHour, name);
    }

    private List<Integer> bruteForceBest(List<Poi> pois, int[][] cost, List<Boolean> locked) {
        Best best = new Best();
        int[] order = new int[pois.size()];
        boolean[] used = new boolean[pois.size()];
        enumerate(0, order, used, pois, cost, locked, best);
        return Arrays.stream(best.order).boxed().toList();
    }

    private void enumerate(int position, int[] order, boolean[] used,
                           List<Poi> pois, int[][] cost, List<Boolean> locked, Best best) {
        if (position == order.length) {
            Score score = score(order, pois, cost);
            if (best.order == null || compare(score, order, best.score, best.order) < 0) {
                best.order = order.clone();
                best.score = score;
            }
            return;
        }

        if (locked.get(position)) {
            order[position] = position;
            used[position] = true;
            enumerate(position + 1, order, used, pois, cost, locked, best);
            used[position] = false;
            return;
        }

        for (int candidate = 0; candidate < order.length; candidate++) {
            if (locked.get(candidate) || used[candidate]) {
                continue;
            }
            order[position] = candidate;
            used[candidate] = true;
            enumerate(position + 1, order, used, pois, cost, locked, best);
            used[candidate] = false;
        }
    }

    private Score score(int[] order, List<Poi> pois, int[][] cost) {
        int clock = DAY_START_HOUR * 60;
        int closed = 0;
        int travel = 0;
        for (int position = 0; position < order.length; position++) {
            if (position > 0) {
                int leg = cost[order[position - 1]][order[position]];
                clock += leg;
                travel += leg;
            }
            Poi poi = pois.get(order[position]);
            int open = poi.isAlwaysOpen() ? 0 : poi.getOpenHour() * 60;
            int close = poi.isAlwaysOpen() ? Integer.MAX_VALUE / 4 : poi.getCloseHour() * 60;
            int visitStart = Math.max(clock, open);
            int leave = visitStart + poi.getAvgVisitMinutes();
            closed += poi.isAlwaysOpen() ? 0 : Math.max(0, leave - Math.max(visitStart, close));
            clock = leave;
        }
        return new Score(closed, clock, travel);
    }

    private int compare(Score a, int[] orderA, Score b, int[] orderB) {
        int closed = Integer.compare(a.closedMinutes(), b.closedMinutes());
        if (closed != 0) {
            return closed;
        }
        int end = Integer.compare(a.endMinutes(), b.endMinutes());
        if (end != 0) {
            return end;
        }
        int travel = Integer.compare(a.travelMinutes(), b.travelMinutes());
        if (travel != 0) {
            return travel;
        }
        for (int i = 0; i < orderA.length; i++) {
            int item = Integer.compare(orderA[i], orderB[i]);
            if (item != 0) {
                return item;
            }
        }
        return 0;
    }

    private static int[][] equalCostMatrix(int size, int offDiagonal) {
        int[][] matrix = new int[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                matrix[i][j] = i == j ? 0 : offDiagonal;
            }
        }
        return matrix;
    }

    private record Score(int closedMinutes, int endMinutes, int travelMinutes) {
    }

    private static final class Best {
        private int[] order;
        private Score score;
    }

    private static final class MatrixRouteProvider implements RouteProvider {
        private final int[][] cost;
        private int lastMatrixSize;
        private int matrixCalls;

        private MatrixRouteProvider(int[][] cost) {
            this.cost = cost;
        }

        @Override
        public int[][] matrix(List<Poi> pois, TravelMode mode) {
            lastMatrixSize = pois.size();
            matrixCalls++;
            return cost;
        }

        @Override
        public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
            throw new UnsupportedOperationException("optimizer unit tests do not build route geometry");
        }

        @Override
        public boolean isReal() {
            return false;
        }
    }
}
