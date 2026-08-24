package com.laioffer.travelplanner.config;

import com.laioffer.travelplanner.service.EstimatedRouteProvider;
import com.laioffer.travelplanner.service.GoogleRoutesProvider;
import com.laioffer.travelplanner.service.OsrmRouteProvider;
import com.laioffer.travelplanner.service.ObservedRouteProvider;
import com.laioffer.travelplanner.service.RouteProvider;
import com.laioffer.travelplanner.service.TravelTimeEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Picks the routing engine at startup, best available first:
 *
 * <ol>
 *   <li><b>Google Routes API</b> when an API key is configured — real geometry and real durations for
 *       walking, transit and driving.</li>
 *   <li><b>OSRM</b> otherwise — real street geometry and road distances with no key, with walking and
 *       transit durations modelled from those real distances.</li>
 *   <li><b>Straight-line estimates</b> if OSRM is disabled or unreachable.</li>
 * </ol>
 *
 * Nothing else in the application knows which one it got; each tier is also the failure fallback for
 * the tier above it, so degradation is graceful rather than fatal.
 */
@Configuration
public class RouteProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(RouteProviderConfig.class);

    @Bean
    public RouteProvider routeProvider(TravelTimeEstimator estimator,
                                       CacheManager cacheManager,
                                       MeterRegistry meterRegistry,
                                       @Value("${travelplanner.google.api-key:}") String googleKey,
                                       @Value("${travelplanner.google.routes-base-url}") String googleUrl,
                                       @Value("${travelplanner.osrm.enabled:true}") boolean osrmEnabled,
                                       @Value("${travelplanner.osrm.base-url}") String osrmUrl) {
        var cache = cacheManager.getCache(CacheConfig.TRAVEL_MATRIX);
        RouteProvider estimated = new EstimatedRouteProvider(estimator);

        if (googleKey != null && !googleKey.isBlank()) {
            log.info("Routing: Google Routes API — real geometry and durations for every mode.");
            return new ObservedRouteProvider(
                    new GoogleRoutesProvider(googleUrl, googleKey, estimated, cache), meterRegistry, "google");
        }
        if (osrmEnabled) {
            log.info("Routing: OSRM at {} — real street geometry, no API key. Walking and transit "
                    + "durations are modelled from real road distance; set travelplanner.google.api-key "
                    + "for fully real numbers.", osrmUrl);
            return new ObservedRouteProvider(
                    new OsrmRouteProvider(osrmUrl, estimated, estimator, cache), meterRegistry, "osrm");
        }
        log.info("Routing: offline straight-line estimates — the map draws direct lines between stops.");
        return new ObservedRouteProvider(estimated, meterRegistry, "estimated");
    }
}
