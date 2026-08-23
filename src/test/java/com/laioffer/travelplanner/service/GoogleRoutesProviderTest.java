package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleRoutesProviderTest {

    @Test
    void usesProviderSpecificMatrixLimits() {
        assertEquals(10, GoogleRoutesProvider.matrixBatchSize(TravelMode.TRANSIT));
        assertEquals(25, GoogleRoutesProvider.matrixBatchSize(TravelMode.DRIVE));
        assertEquals(25, GoogleRoutesProvider.matrixBatchSize(TravelMode.WALK));
    }

    @Test
    void blocksTransitMatrixAtOneHundredElements() {
        assertBlockShapes(10, 10, "10x10");
        assertBlockShapes(13, 10, "10x10", "10x3", "3x10", "3x3");
        assertBlockShapes(25, 10,
                "10x10", "10x10", "10x5",
                "10x10", "10x10", "10x5",
                "5x10", "5x10", "5x5");
    }

    @Test
    void wrapsMatrixLocationsAsRouteMatrixWaypoints() {
        Poi poi = new Poi(null, "Test POI", "Landmark", 40.7128, -74.0060,
                4.5, 60, 9, 17, "test");

        List<Map<String, Object>> locations = GoogleRoutesProvider.matrixLocations(List.of(poi));

        assertEquals(1, locations.size());
        assertTrue(locations.getFirst().containsKey("waypoint"));
        @SuppressWarnings("unchecked")
        Map<String, Object> waypoint = (Map<String, Object>) locations.getFirst().get("waypoint");
        assertTrue(waypoint.containsKey("location"));
    }

    private static void assertBlockShapes(int stops, int batchSize, String... expectedShapes) {
        List<String> actual = GoogleRoutesProvider.matrixBlocks(stops, batchSize).stream()
                .map(block -> block.sourceCount() + "x" + block.destinationCount())
                .toList();
        assertEquals(List.of(expectedShapes), actual);
    }
}
