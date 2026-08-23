package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedRouteProviderTest {

    @Test
    void recordsProviderOperationWithBoundedDiagnosticTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RouteProvider delegate = new RouteProvider() {
            public int[][] matrix(List<Poi> pois, TravelMode mode) { return new int[0][0]; }
            public List<TravelLeg> legs(List<Poi> pois, TravelMode mode) { return List.of(); }
            public boolean isReal() { return false; }
        };
        ObservedRouteProvider provider = new ObservedRouteProvider(delegate, registry, "estimated");

        provider.matrix(List.of(), TravelMode.WALK);

        assertThat(registry.get("travelplanner.route.provider")
                .tags("provider", "estimated", "operation", "matrix",
                        "mode", "walk", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }
}
