package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.TravelMode;
import org.slf4j.Logger;

/** Emits one consistently shaped measurement for every route-provider operation. */
final class RouteProviderProfiler {

    private RouteProviderProfiler() {
    }

    static long start() {
        return System.nanoTime();
    }

    static void record(Logger log, String provider, String operation, TravelMode mode,
                       int stops, int elements,
                       long startedNanos, boolean cacheHit, boolean fallback, String outcome) {
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        log.info("route_provider_call provider={} operation={} mode={} stops={} elements={} duration_ms={} "
                        + "cache_hit={} fallback={} outcome={}",
                provider, operation, mode, stops, elements, durationMs, cacheHit, fallback, outcome);
    }

    static void recordBatch(Logger log, String provider, TravelMode mode, int stops,
                            int batchIndex, int batchCount, int origins, int destinations,
                            long startedNanos, boolean fallback, String outcome) {
        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        log.info("route_provider_call provider={} operation=matrix_block mode={} stops={} elements={} "
                        + "batch_index={} batch_count={} origins={} destinations={} duration_ms={} "
                        + "cache_hit=false fallback={} outcome={}",
                provider, mode, stops, origins * destinations, batchIndex, batchCount,
                origins, destinations, durationMs, fallback, outcome);
    }
}
