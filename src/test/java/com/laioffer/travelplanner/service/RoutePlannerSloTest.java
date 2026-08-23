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

/** Fixed-matrix smoke benchmark for the Week 3 exact-algorithm latency SLO. */
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
        List<Integer> expected = planner.optimizeOrder(
                pois, TravelMode.TRANSIT, 9, unlocked);
        planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, unlocked);

        long[] elapsedNanos = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long started = System.nanoTime();
            List<Integer> actual = planner.optimizeOrder(
                    pois, TravelMode.TRANSIT, 9, unlocked);
            elapsedNanos[sample] = System.nanoTime() - started;
            assertEquals(expected, actual, "benchmark result must remain deterministic");
        }

        Arrays.sort(elapsedNanos);
        long p95Nanos = elapsedNanos[(int) Math.ceil(SAMPLES * 0.95) - 1];
        double p95Millis = p95Nanos / 1_000_000.0;
        System.out.printf("exact-12 fixed-matrix P95: %.1f ms (%d samples)%n", p95Millis, SAMPLES);
        assertTrue(p95Nanos <= 500_000_000L,
                () -> "fixed-matrix exact P95 was %.1f ms (SLO: <= 500 ms)".formatted(p95Millis));

        assertEquals(STOP_COUNT, expected.size());
        assertEquals(STOP_COUNT, expected.stream().distinct().count());
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
