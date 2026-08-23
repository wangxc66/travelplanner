package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real routes from the Google Routes API.
 *
 * <p>Two calls, and only two:
 * <ul>
 *   <li><b>computeRouteMatrix</b> — the n×n travel-time matrix the day optimizer searches over.</li>
 *   <li><b>computeRoutes</b> — one call for a whole ordered day, passing the middle stops as
 *       {@code intermediates}, which comes back with a per-leg duration, distance and encoded
 *       polyline. That is the geometry the map draws, so the route follows actual streets and rail
 *       instead of cutting through buildings.</li>
 * </ul>
 *
 * <p>Both results are cached on the exact coordinate set plus travel mode, so repeatedly hitting
 * Optimize on the same day costs nothing after the first call. Any failure — bad key, quota, network,
 * a pair with no route — degrades to the offline estimator for that request rather than breaking the
 * planner.
 */
public class GoogleRoutesProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleRoutesProvider.class);

    private static final String MATRIX_PATH = "/distanceMatrix/v2:computeRouteMatrix";
    private static final String ROUTES_PATH = "/directions/v2:computeRoutes";
    private static final String MATRIX_FIELDS = "originIndex,destinationIndex,duration,condition";
    private static final String ROUTES_FIELDS =
            "routes.legs.duration,routes.legs.distanceMeters,routes.legs.polyline.encodedPolyline";

    /** computeRoutes allows far more, but a single day never needs it. */
    private static final int MAX_INTERMEDIATES = 23;

    private final RestClient client;
    private final RouteProvider fallback;
    private final Cache cache;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public GoogleRoutesProvider(String baseUrl, String apiKey, RouteProvider fallback, Cache cache) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get("correlation_id");
                    if (correlationId != null) {
                        request.getHeaders().set("X-Correlation-ID", correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
        this.fallback = fallback;
        this.cache = cache;
    }

    @Override
    public boolean isReal() {
        return true;
    }

    // ------------------------------------------------------------------ matrix

    @Override
    public int[][] matrix(List<Poi> pois, TravelMode mode) {
        if (pois.size() < 2) {
            return new int[pois.size()][pois.size()];
        }
        String key = cacheKey("matrix", pois, mode);
        int[][] cached = cache.get(key, int[][].class);
        if (cached != null) {
            return cached;
        }
        int[][] estimated = fallback.matrix(pois, mode);
        try {
            int[][] real = requestMatrix(pois, mode, estimated);
            cache.put(key, real);
            return real;
        } catch (Exception e) {
            degrade("route matrix", e);
            return estimated;
        }
    }

    /** @param estimated used to fill any pair Google cannot route, so the matrix is never sparse. */
    private int[][] requestMatrix(List<Poi> pois, TravelMode mode, int[][] estimated) {
        List<Map<String, Object>> waypoints = pois.stream().map(GoogleRoutesProvider::waypoint).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("origins", waypoints);
        body.put("destinations", waypoints);
        body.put("travelMode", mode.name());
        departureTime(pois).ifPresent(t -> body.put("departureTime", t));

        List<Map<String, Object>> elements = client.post()
                .uri(MATRIX_PATH)
                .header("X-Goog-FieldMask", MATRIX_FIELDS)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        int n = pois.size();
        int[][] cost = new int[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(estimated[i], 0, cost[i], 0, n);
        }
        if (elements == null || elements.isEmpty()) {
            throw new IllegalStateException("computeRouteMatrix returned no elements");
        }
        for (Map<String, Object> element : elements) {
            int from = number(element.get("originIndex"), -1);
            int to = number(element.get("destinationIndex"), -1);
            if (from < 0 || to < 0 || from >= n || to >= n) {
                continue;
            }
            String condition = string(element.get("condition"));
            if (condition != null && !condition.isEmpty() && !"ROUTE_EXISTS".equals(condition)) {
                continue; // keep the estimate for unroutable pairs
            }
            cost[from][to] = from == to ? 0 : Math.max(1, seconds(element.get("duration")) / 60);
        }
        return cost;
    }

    // ------------------------------------------------------------------ legs of one ordered day

    @Override
    public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
        if (ordered.size() < 2) {
            return List.of();
        }
        if (ordered.size() - 2 > MAX_INTERMEDIATES) {
            return fallback.legs(ordered, mode);
        }
        String key = cacheKey("legs", ordered, mode);
        @SuppressWarnings("unchecked")
        List<TravelLeg> cached = cache.get(key, List.class);
        if (cached != null) {
            return cached;
        }
        try {
            List<TravelLeg> real = requestLegs(ordered, mode);
            cache.put(key, real);
            return real;
        } catch (Exception e) {
            degrade("route geometry", e);
            return fallback.legs(ordered, mode);
        }
    }

    private List<TravelLeg> requestLegs(List<Poi> ordered, TravelMode mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("origin", waypoint(ordered.getFirst()));
        body.put("destination", waypoint(ordered.getLast()));
        if (ordered.size() > 2) {
            body.put("intermediates", ordered.subList(1, ordered.size() - 1).stream()
                    .map(GoogleRoutesProvider::waypoint)
                    .toList());
        }
        body.put("travelMode", mode.name());
        body.put("polylineEncoding", "ENCODED_POLYLINE");
        departureTime(ordered).ifPresent(t -> body.put("departureTime", t));

        Map<String, Object> response = client.post()
                .uri(ROUTES_PATH)
                .header("X-Goog-FieldMask", ROUTES_FIELDS)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        List<Map<String, Object>> legs = legsOf(response);
        if (legs.size() != ordered.size() - 1) {
            throw new IllegalStateException("computeRoutes returned " + legs.size()
                    + " legs, expected " + (ordered.size() - 1));
        }

        List<TravelLeg> result = new ArrayList<>(legs.size());
        for (Map<String, Object> leg : legs) {
            int minutes = Math.max(1, seconds(leg.get("duration")) / 60);
            double km = Math.round(number(leg.get("distanceMeters"), 0) / 100.0) / 10.0;
            String polyline = null;
            if (leg.get("polyline") instanceof Map<?, ?> geometry) {
                polyline = string(geometry.get("encodedPolyline"));
            }
            result.add(new TravelLeg(minutes, km, polyline));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> legsOf(Map<String, Object> response) {
        if (response == null || !(response.get("routes") instanceof List<?> routes) || routes.isEmpty()) {
            return List.of();
        }
        if (!(routes.getFirst() instanceof Map<?, ?> route) || !(route.get("legs") instanceof List<?> legs)) {
            return List.of();
        }
        return (List<Map<String, Object>>) legs;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> waypoint(Poi poi) {
        return Map.of("location", Map.of("latLng",
                Map.of("latitude", poi.getLat(), "longitude", poi.getLng())));
    }

    /** Durations are returned as {@code "1234s"}. */
    private static int seconds(Object duration) {
        String raw = string(duration);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int number(Object value, int fallbackValue) {
        return value instanceof Number n ? n.intValue() : fallbackValue;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Transit and traffic-aware driving times depend on when you travel. We ask for the next 09:00 in
     * the city's own timezone — a representative sightseeing departure — rather than "now", which
     * would price a Tokyo itinerary against 3 a.m. service levels.
     */
    private static java.util.Optional<String> departureTime(List<Poi> pois) {
        if (pois.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            ZoneId zone = ZoneId.of(pois.getFirst().getCity().getTimezone());
            ZonedDateTime next = ZonedDateTime.now(zone).with(LocalTime.of(9, 0));
            if (!next.toInstant().isAfter(Instant.now())) {
                next = next.plusDays(1);
            }
            return java.util.Optional.of(next.toInstant().toString());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static String cacheKey(String prefix, List<Poi> pois, TravelMode mode) {
        StringBuilder sb = new StringBuilder(prefix).append('|').append(mode).append('|');
        for (Poi poi : pois) {
            sb.append(String.format(Locale.ROOT, "%.5f,%.5f;", poi.getLat(), poi.getLng()));
        }
        return sb.toString();
    }

    private void degrade(String what, Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.warn("Google Routes API unavailable for {} — falling back to offline estimates. "
                    + "Check travelplanner.google.api-key and that Routes API is enabled. Cause: {}",
                    what, e.getClass().getSimpleName());
        } else {
            log.debug("Google Routes API call for {} failed: {}", what, e.getClass().getSimpleName());
        }
    }
}
