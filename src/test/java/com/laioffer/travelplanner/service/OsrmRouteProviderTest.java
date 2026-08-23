package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.TravelMode;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OsrmRouteProviderTest {

    private final TravelTimeEstimator estimator = new TravelTimeEstimator();
    private final EstimatedRouteProvider fallback = new EstimatedRouteProvider(estimator);

    @Test
    void createsExpectedBlocksAtAndAroundTheBoundary() {
        assertBlockShapes(13, "13x13");
        assertBlockShapes(25, "25x25");
        assertBlockShapes(26, "25x25", "25x1", "1x25", "1x1");
        assertBlockShapes(50, "25x25", "25x25", "25x25", "25x25");
    }

    @Test
    void appliesOneBlockWithoutOverwritingFallbackCellsOutsideIt() {
        OsrmRouteProvider provider = new OsrmRouteProvider("http://localhost", fallback, estimator,
                new ConcurrentMapCache("test"), 25);
        int[][] cost = new int[4][4];
        for (int[] row : cost) {
            Arrays.fill(row, 99);
        }
        OsrmRouteProvider.MatrixBlock block = new OsrmRouteProvider.MatrixBlock(0, 2, 2, 4);
        List<List<Number>> durations = List.of(List.of(60, 120), List.of(180, 240));
        List<List<Number>> distances = List.of(List.of(1000, 2000), List.of(3000, 4000));

        provider.applyBlock(cost, durations, distances, block, TravelMode.DRIVE);

        assertEquals(5, cost[0][2]);
        assertEquals(6, cost[0][3]);
        assertEquals(7, cost[1][2]);
        assertEquals(8, cost[1][3]);
        assertEquals(99, cost[2][0], "a different block's fallback value was overwritten");
        assertEquals(99, cost[3][3], "a different block's diagonal was overwritten");
    }

    private static void assertBlockShapes(int stops, String... expectedShapes) {
        List<String> actual = OsrmRouteProvider.matrixBlocks(stops, 25).stream()
                .map(block -> block.sourceCount() + "x" + block.destinationCount())
                .toList();
        assertEquals(List.of(expectedShapes), actual);
    }
}
