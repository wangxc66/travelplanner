package com.laioffer.travelplanner.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the controller boundary with the real security filter, database and seeded catalog.
 * The routing provider is kept offline so these tests are repeatable without external services.
 */
@SpringBootTest(properties = {
        "travelplanner.h2.tcp.enabled=false",
        "travelplanner.osrm.enabled=false"
})
@AutoConfigureMockMvc
class ApiIntegrationContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registeredUserCanCreateAndListOnlyTheirOwnTrip() throws Exception {
        String token = register("trip-owner");
        long cityId = firstCityId();

        long tripId = createTrip(token, cityId, "Integration trip");

        mockMvc.perform(get("/api/trips").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tripId))
                .andExpect(jsonPath("$[0].title").value("Integration trip"));
    }

    @Test
    void anotherAuthenticatedUserCannotReadSomeoneElsesTrip() throws Exception {
        String ownerToken = register("owner");
        String otherToken = register("other");
        long tripId = createTrip(ownerToken, firstCityId(), "Private trip");

        mockMvc.perform(get("/api/trips/{tripId}", tripId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("error.tripNotFound"))
                .andExpect(jsonPath("$.params").isMap());
    }

    @Test
    void duplicatePoiIsReportedAsTheDocumentedConflict() throws Exception {
        String token = register("duplicate-poi");
        long cityId = firstCityId();
        long tripId = createTrip(token, cityId, "Duplicate POI trip");
        long poiId = firstPoiId(cityId);
        String body = objectMapper.writeValueAsString(Map.of("poiId", poiId, "dayIndex", 1));

        mockMvc.perform(post("/api/trips/{tripId}/items", tripId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/trips/{tripId}/items", tripId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("error.poiAlreadyPlanned"))
                .andExpect(jsonPath("$.params.name").isNotEmpty());
    }

    @Test
    void malformedJsonUsesTheDocumentedInvalidRequestEnvelope() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.invalidRequest"))
                .andExpect(jsonPath("$.params").isMap());
    }

    private String register(String prefix) throws Exception {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", "IntegrationPass123",
                "displayName", "API Integration Test"));
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        return json(result).path("token").asText();
    }

    private long firstCityId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andReturn();
        return json(result).get(0).path("id").asLong();
    }

    private long firstPoiId(long cityId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cities/{cityId}/pois", cityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andReturn();
        return json(result).get(0).path("id").asLong();
    }

    private long createTrip(String token, long cityId, String title) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "cityId", cityId,
                "title", title,
                "startDate", "2026-09-01",
                "numDays", 2));
        MvcResult result = mockMvc.perform(post("/api/trips")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn();
        return json(result).path("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
