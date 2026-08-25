package com.laioffer.travelplanner.web;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the checked-in API contract parseable and complete at the controller boundary. */
class OpenApiDocumentTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void contractParsesAndDocumentsEveryCurrentOperation() throws IOException {
        Object parsed = new Yaml().load(Files.readString(Path.of("docs/openapi.yaml")));
        assertTrue(parsed instanceof Map<?, ?>, "OpenAPI document must be a YAML object");

        Map<?, ?> document = (Map<?, ?>) parsed;
        assertEquals("3.0.3", document.get("openapi"));
        assertTrue(document.get("paths") instanceof Map<?, ?>, "OpenAPI document must define paths");

        Map<?, ?> paths = (Map<?, ?>) document.get("paths");
        long operationCount = paths.values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .flatMap(path -> path.keySet().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(HTTP_METHODS::contains)
                .count();

        assertEquals(18, operationCount);
        assertTrue(paths.containsKey("/auth/login"));
        assertTrue(paths.containsKey("/api/cities/{cityId}/pois"));
        assertTrue(paths.containsKey("/api/trips/{tripId}/days/{dayIndex}/optimize"));
        assertTrue(paths.containsKey("/api/trips/{tripId}/rebalance"));
    }
}
