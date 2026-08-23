package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.function.Supplier;

/** Records every route-provider operation using bounded tags safe for a metrics backend. */
public final class ObservedRouteProvider implements RouteProvider {

    private final RouteProvider delegate;
    private final MeterRegistry registry;
    private final String provider;

    public ObservedRouteProvider(RouteProvider delegate, MeterRegistry registry, String provider) {
        this.delegate = delegate;
        this.registry = registry;
        this.provider = provider;
    }

    @Override
    public int[][] matrix(List<Poi> pois, TravelMode mode) {
        return record("matrix", mode, () -> delegate.matrix(pois, mode));
    }

    @Override
    public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
        return record("legs", mode, () -> delegate.legs(ordered, mode));
    }

    @Override
    public boolean isReal() {
        return delegate.isReal();
    }

    private <T> T record(String operation, TravelMode mode, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            sample.stop(Timer.builder("travelplanner.route.provider")
                    .description("Route provider operation duration")
                    .tag("provider", provider)
                    .tag("operation", operation)
                    .tag("mode", mode.name().toLowerCase(java.util.Locale.ROOT))
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }
}
