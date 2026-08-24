package com.laioffer.travelplanner.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RoutePlannerSpringWiringTest {

    @Test
    void springUsesTheProductionRouteProviderConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RouteProvider.class,
                    () -> new EstimatedRouteProvider(new TravelTimeEstimator()));
            context.register(RoutePlanner.class);
            context.refresh();

            assertNotNull(context.getBean(RoutePlanner.class));
        }
    }
}
