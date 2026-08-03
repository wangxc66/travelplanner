package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real street geometry with no API key, from an OSRM server.
 *
 * <p>Mirrors the two calls the Google provider makes: {@code /table} for the travel-time matrix and a
 * single {@code /route} per ordered day for the legs. OSRM returns geometry per navigation step, so
 * each itinerary leg is stitched back together with {@link PolylineCodec#join}.
 *
 * <p><b>What is real and what is modelled.</b> The path and the road distance are real. Durations are
 * OSRM's own for {@code DRIVE}. For {@code WALK} and {@code TRANSIT} they are derived from the real
 * road distance and the mode's speed model, because the public demo server's pedestrian profile
 * returns car-like times and OSRM has no notion of trains at all. Transit therefore borrows the
 * driving corridor for its shape — far closer to the truth than a straight line across a bay, but not
 * a rail alignment. With a Google key configured, {@link GoogleRoutesProvider} supersedes all of this
 * and every number becomes real.
 *
 * <p>The public server at router.project-osrm.org is a courtesy demo with no SLA and is explicitly not
 * for production; point {@code travelplanner.osrm.base-url} at your own instance for anything serious.
 */
public class OsrmRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(OsrmRouteProvider.class);

    private final RestClient client;
    private final RouteProvider fallback;
    private final TravelTimeEstimator estimator;
    private final Cache cache;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public OsrmRouteProvider(String baseUrl, RouteProvider fallback, TravelTimeEstimator estimator, Cache cache) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeouts())
                .build();
        this.fallback = fallback;
        this.estimator = estimator;
        this.cache = cache;
    }

    private static ClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return factory;
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
        String key = "osrm-matrix|" + mode + '|' + coordinates(pois);
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
            degrade("travel matrix", e);
            return estimated;
        }
    }

    private int[][] requestMatrix(List<Poi> pois, TravelMode mode, int[][] estimated) {
        Map<String, Object> response = client.get()
                .uri("/table/v1/{profile}/{coords}?annotations=duration,distance",
                        profile(mode), coordinates(pois))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        require(response);

        List<List<Number>> durations = rows(response.get("durations"));
        List<List<Number>> distances = rows(response.get("distances"));
        int n = pois.size();
        int[][] cost = new int[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(estimated[i], 0, cost[i], 0, n);
        }
        for (int i = 0; i < n && i < durations.size(); i++) {
            List<Number> row = durations.get(i);
            for (int j = 0; j < n && j < row.size(); j++) {
                if (i == j) {
                    cost[i][j] = 0;
                    continue;
                }
                Integer minutes = minutesFor(mode, row.get(j), cell(distances, i, j));
                if (minutes != null) {
                    cost[i][j] = Math.max(1, minutes);
                }
            }
        }
        return cost;
    }

    // ------------------------------------------------------------------ legs

    @Override
    public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
        if (ordered.size() < 2) {
            return List.of();
        }
        String key = "osrm-legs|" + mode + '|' + coordinates(ordered);
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
        Map<String, Object> response = client.get()
                .uri("/route/v1/{profile}/{coords}?overview=false&steps=true&geometries=polyline",
                        profile(mode), coordinates(ordered))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        require(response);

        List<Map<String, Object>> legs = legsOf(response);
        if (legs.size() != ordered.size() - 1) {
            throw new IllegalStateException("OSRM returned " + legs.size() + " legs, expected "
                    + (ordered.size() - 1));
        }

        List<TravelLeg> result = new ArrayList<>(legs.size());
        for (Map<String, Object> leg : legs) {
            double km = asDouble(leg.get("distance")) / 1000.0;
            Integer minutes = minutesFor(mode, leg.get("duration"), leg.get("distance"));
            result.add(new TravelLeg(
                    Math.max(1, minutes == null ? 1 : minutes),
                    Math.round(km * 10) / 10.0,
                    geometryOf(leg)));
        }
        return result;
    }

    /** Concatenates the step geometries of one leg into a single encoded polyline. */
    @SuppressWarnings("unchecked")
    private static String geometryOf(Map<String, Object> leg) {
        if (!(leg.get("steps") instanceof List<?> steps)) {
            return null;
        }
        List<String> segments = new ArrayList<>(steps.size());
        for (Object step : steps) {
            if (step instanceof Map<?, ?> map && map.get("geometry") instanceof String geometry) {
                segments.add(geometry);
            }
        }
        return segments.isEmpty() ? null : PolylineCodec.join(segments);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * OSRM's driving durations are genuine routing output. Its pedestrian times are not trustworthy on
     * the public demo server, and it knows nothing about transit — so those two modes are timed from
     * the real road distance instead, which is still a large improvement on straight-line distance.
     */
    private Integer minutesFor(TravelMode mode, Object durationSeconds, Object distanceMeters) {
        if (mode == TravelMode.DRIVE && durationSeconds instanceof Number seconds) {
            return (int) Math.round(seconds.doubleValue() / 60) + mode.getOverheadMinutes();
        }
        if (distanceMeters instanceof Number meters) {
            return estimator.minutesForKm(meters.doubleValue() / 1000.0, mode);
        }
        return null;
    }

    /** OSRM has car, bike and foot. Transit borrows the road network for its shape. */
    private static String profile(TravelMode mode) {
        return mode == TravelMode.WALK ? "foot" : "driving";
    }

    /** OSRM wants lng,lat — the opposite order of everything else here. */
    private static String coordinates(List<Poi> pois) {
        StringJoiner joiner = new StringJoiner(";");
        for (Poi poi : pois) {
            joiner.add(String.format(Locale.ROOT, "%.5f,%.5f", poi.getLng(), poi.getLat()));
        }
        return joiner.toString();
    }

    private static void require(Map<String, Object> response) {
        if (response == null || !"Ok".equals(String.valueOf(response.get("code")))) {
            throw new IllegalStateException("OSRM replied "
                    + (response == null ? "nothing" : response.get("code")));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> legsOf(Map<String, Object> response) {
        if (!(response.get("routes") instanceof List<?> routes) || routes.isEmpty()) {
            return List.of();
        }
        if (!(routes.getFirst() instanceof Map<?, ?> route) || !(route.get("legs") instanceof List<?> legs)) {
            return List.of();
        }
        return (List<Map<String, Object>>) legs;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Number>> rows(Object value) {
        return value instanceof List<?> list ? (List<List<Number>>) list : List.of();
    }

    private static Object cell(List<List<Number>> table, int i, int j) {
        if (i >= table.size()) {
            return null;
        }
        List<Number> row = table.get(i);
        return j < row.size() ? row.get(j) : null;
    }

    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private void degrade(String what, Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.warn("OSRM unavailable for {} — falling back to straight-line estimates. Cause: {}",
                    what, e.getMessage());
        } else {
            log.debug("OSRM call for {} failed: {}", what, e.getMessage());
        }
    }
}
