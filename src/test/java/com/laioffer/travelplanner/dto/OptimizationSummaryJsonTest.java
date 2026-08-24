package com.laioffer.travelplanner.dto;

import com.laioffer.travelplanner.dto.Dtos.OptimizationMetricsDto;
import com.laioffer.travelplanner.dto.Dtos.OptimizationObjectiveDto;
import com.laioffer.travelplanner.dto.Dtos.OptimizationSummaryDto;
import com.laioffer.travelplanner.dto.Dtos.TripDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptimizationSummaryJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesAdditiveOptimizationEvidenceIncludingTravelTieBreak() throws Exception {
        OptimizationSummaryDto summary = new OptimizationSummaryDto(
                1, "TRANSIT", "HELD_KARP", true, true,
                new OptimizationObjectiveDto(0, 1195, 124),
                new OptimizationObjectiveDto(0, 1195, 100),
                new OptimizationMetricsDto(6, 25_000_000L,
                        100, 60, 40, 3, 25));
        TripDto trip = new TripDto(7L, "Tokyo", null, "2026-08-23", 1,
                9, "TRANSIT", List.of(), List.of(), 6, List.of(summary));

        JsonNode root = mapper.readTree(mapper.writeValueAsString(trip));
        JsonNode result = root.get("optimizationResults").get(0);

        assertEquals("HELD_KARP", result.get("algorithm").asString());
        assertEquals(124, result.get("before").get("travelMinutes").asInt());
        assertEquals(100, result.get("after").get("travelMinutes").asInt());
        assertEquals(25, result.get("metrics").get("peakFrontierLabelsInLayer").asInt());
    }

    @Test
    void keepsTheExistingTripConstructorSourceCompatibleWithAnEmptyResultList() throws Exception {
        TripDto trip = new TripDto(7L, "Tokyo", null, "2026-08-23", 1,
                9, "TRANSIT", List.of(), List.of(), 0);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(trip));

        assertEquals(0, root.get("optimizationResults").size());
    }
}
