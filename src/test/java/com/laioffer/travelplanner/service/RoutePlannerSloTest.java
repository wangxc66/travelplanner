package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed-matrix benchmark matrix for the Week 4 exact-algorithm latency SLO. */
class RoutePlannerSloTest {

    private static final int STOP_COUNT = 12;
    private static final int SAMPLES = 20;

    @Test
    void exactTwelveStopP95ExcludesProviderAndStaysWithinFiveHundredMilliseconds() {
        City city = new City("Benchmark", "Test", "UTC", 0, 0, 12, "*");
        List<Poi> pois = new ArrayList<>();
        for (int i = 0; i < STOP_COUNT; i++) {
            int open = 8 + i % 4;
            int close = 14 + i % 7;
            int visit = 30 + (i % 3) * 15;
            pois.add(new Poi(city, "P" + i, "Landmark", 0, 0, 4.5,
                    visit, open, close, "benchmark"));
        }

        int[][] matrix = deterministicMatrix(STOP_COUNT);
        RoutePlanner planner = new RoutePlanner(new FixedMatrixProvider(matrix));
        List<Boolean> unlocked = Collections.nCopies(STOP_COUNT, false);

        // Warm the JIT; network/provider latency is deliberately absent from this benchmark.
        RoutePlanner.OptimizationResult baseline = planner.optimizeDetailed(
                pois, TravelMode.TRANSIT, 9, unlocked);
        List<Integer> expected = baseline.order();
        planner.optimizeDetailed(pois, TravelMode.TRANSIT, 9, unlocked);

        long[] elapsedNanos = new long[SAMPLES];
        long maxGenerated = 0;
        long maxAccepted = 0;
        long maxPeakLayerLabels = 0;
        int maxFrontier = 0;
        for (int sample = 0; sample < SAMPLES; sample++) {
            RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                    pois, TravelMode.TRANSIT, 9, unlocked);
            elapsedNanos[sample] = result.metrics().algorithmNanos();
            maxGenerated = Math.max(maxGenerated, result.metrics().generatedLabels());
            maxAccepted = Math.max(maxAccepted, result.metrics().acceptedLabels());
            maxPeakLayerLabels = Math.max(
                    maxPeakLayerLabels, result.metrics().peakFrontierLabelsInLayer());
            maxFrontier = Math.max(maxFrontier, result.metrics().maxFrontierSize());
            assertEquals(expected, result.order(), "benchmark result must remain deterministic");
            assertEquals(RoutePlanner.Algorithm.HELD_KARP, result.algorithm());
            assertTrue(result.optimal());
        }

        Arrays.sort(elapsedNanos);
        long p50Nanos = elapsedNanos[(int) Math.ceil(SAMPLES * 0.50) - 1];
        long p95Nanos = elapsedNanos[(int) Math.ceil(SAMPLES * 0.95) - 1];
        double p50Millis = p50Nanos / 1_000_000.0;
        double p95Millis = p95Nanos / 1_000_000.0;
        System.out.printf("PERF exact-12 fixed-matrix p50=%.1fms p95=%.1fms samples=%d; "
                        + "labels generated/accepted=%d/%d, max state labels=%d, peak layer labels=%d%n",
                p50Millis, p95Millis, SAMPLES,
                maxGenerated, maxAccepted, maxFrontier, maxPeakLayerLabels);
        assertTrue(p50Nanos <= p95Nanos);
        assertTrue(p95Nanos <= 500_000_000L,
                () -> "fixed-matrix exact P95 was %.1f ms (SLO: <= 500 ms)".formatted(p95Millis));
        assertTrue(maxGenerated >= maxAccepted && maxAccepted > 0);
        assertTrue(maxFrontier >= 1);
        assertTrue(maxPeakLayerLabels >= 1);

        assertEquals(STOP_COUNT, expected.size());
        assertEquals(STOP_COUNT, expected.stream().distinct().count());
    }

    @Test
    void reportsSmallAndMediumExactScalingFixtures() {
        for (int stopCount : List.of(4, 8)) {
            City city = new City("Benchmark", "Test", "UTC", 0, 0, 12, "*");
            List<Poi> pois = new ArrayList<>();
            for (int i = 0; i < stopCount; i++) {
                pois.add(new Poi(city, "P" + i, "Landmark", 0, 0, 4.5,
                        30 + (i % 3) * 15, 8 + i % 4, 14 + i % 7, "benchmark"));
            }
            RoutePlanner planner = new RoutePlanner(new FixedMatrixProvider(deterministicMatrix(stopCount)));
            List<Boolean> unlocked = Collections.nCopies(stopCount, false);

            planner.optimizeDetailed(pois, TravelMode.TRANSIT, 9, unlocked);
            long[] samples = new long[10];
            RoutePlanner.OptimizationResult last = null;
            for (int sample = 0; sample < samples.length; sample++) {
                last = planner.optimizeDetailed(pois, TravelMode.TRANSIT, 9, unlocked);
                samples[sample] = last.metrics().algorithmNanos();
            }
            Arrays.sort(samples);
            double p50Millis = samples[4] / 1_000_000.0;
            double p95Millis = samples[9] / 1_000_000.0;
            System.out.printf("exact-%d fixed-matrix P50/P95: %.1f/%.1f ms; "
                            + "labels generated/accepted=%d/%d, max state labels=%d, peak layer labels=%d%n",
                    stopCount, p50Millis, p95Millis,
                    last.metrics().generatedLabels(), last.metrics().acceptedLabels(),
                    last.metrics().maxFrontierSize(), last.metrics().peakFrontierLabelsInLayer());
            assertEquals(RoutePlanner.Algorithm.HELD_KARP, last.algorithm());
            assertTrue(last.optimal());
            assertTrue(samples[9] <= 500_000_000L);
        }
    }

    @Test
    void heuristicTwentyFiveStopP95StaysWithinOneHundredMilliseconds() {
        int stopCount = 25;
        City city = new City("Benchmark", "Test", "UTC", 0, 0, 12, "*");
        List<Poi> pois = new ArrayList<>();
        for (int i = 0; i < stopCount; i++) {
            pois.add(new Poi(city, "P" + i, "Landmark", 0, 0, 4.5,
                    30 + (i % 3) * 15, 8 + i % 4, 16 + i % 7, "benchmark"));
        }
        RoutePlanner planner = new RoutePlanner(new FixedMatrixProvider(deterministicMatrix(stopCount)));
        List<Boolean> unlocked = Collections.nCopies(stopCount, false);
        List<Integer> expected = planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, unlocked);
        planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, unlocked);

        long[] elapsedNanos = new long[50];
        for (int sample = 0; sample < elapsedNanos.length; sample++) {
            long started = System.nanoTime();
            assertEquals(expected, planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, unlocked));
            elapsedNanos[sample] = System.nanoTime() - started;
        }
        Arrays.sort(elapsedNanos);
        double p50Millis = elapsedNanos[24] / 1_000_000.0;
        double p95Millis = elapsedNanos[47] / 1_000_000.0;
        System.out.printf("PERF heuristic-25 fixed-matrix p50=%.1fms p95=%.1fms samples=%d%n",
                p50Millis, p95Millis, elapsedNanos.length);
        assertTrue(elapsedNanos[47] <= 100_000_000L,
                () -> "heuristic-25 fixed-matrix p95 was %.1f ms (SLO: <= 100 ms)".formatted(p95Millis));
    }

    private static int[][] deterministicMatrix(int size) {
        int[][] matrix = new int[size][size];
        for (int from = 0; from < size; from++) {
            for (int to = 0; to < size; to++) {
                matrix[from][to] = from == to
                        ? 0
                        : 5 + ((from + 1) * 17 + (to + 1) * 31 + from * to * 7) % 45;
            }
        }
        return matrix;
    }

    private record FixedMatrixProvider(int[][] cost) implements RouteProvider {
        @Override
        public int[][] matrix(List<Poi> pois, TravelMode mode) {
            return cost;
        }

        @Override
        public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
            throw new UnsupportedOperationException("the optimizer SLO excludes route-provider legs");
        }

        @Override
        public boolean isReal() {
            return false;
        }
    }
}