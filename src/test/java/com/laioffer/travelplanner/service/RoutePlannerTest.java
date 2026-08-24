package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerTest {

    private final RouteProvider routes = new EstimatedRouteProvider(new TravelTimeEstimator());
    private final RoutePlanner planner = new RoutePlanner(routes);
    private final City city = new City("Test", "Test", "UTC", 0, 0, 12, "*");

    private Poi poi(String name, double lat, double lng, int visitMinutes, int open, int close) {
        return new Poi(city, name, "Landmark", lat, lng, 4.5, visitMinutes, open, close, name);
    }

    /** Five stops laid out along a line, handed to the planner in the worst possible order. */
    @Test
    void ordersStopsAlongALineIntoOneSweep() {
        List<Poi> pois = List.of(
                poi("C", 35.70, 139.80, 30, 0, 24),
                poi("E", 35.72, 139.82, 30, 0, 24),
                poi("A", 35.68, 139.78, 30, 0, 24),
                poi("D", 35.71, 139.81, 30, 0, 24),
                poi("B", 35.69, 139.79, 30, 0, 24));

        List<String> names = planner.optimizeOrder(pois, TravelMode.WALK, 9, false).stream()
                .map(i -> pois.get(i).getName())
                .toList();

        // Either direction of the sweep is optimal; what matters is that it is monotonic.
        List<String> reversed = new ArrayList<>(names);
        Collections.reverse(reversed);
        assertTrue(names.equals(List.of("A", "B", "C", "D", "E"))
                        || reversed.equals(List.of("A", "B", "C", "D", "E")),
                "expected a single sweep, got " + names);
    }

    /** A venue that only opens in the evening must not be scheduled first thing in the morning. */
    @Test
    void pushesEveningOnlyVenueToTheEndOfTheDay() {
        List<Poi> pois = List.of(
                poi("Bar", 35.694, 139.705, 90, 18, 24),
                poi("Shrine", 35.715, 139.797, 60, 6, 17),
                poi("Museum", 35.719, 139.777, 120, 9, 17),
                poi("Park", 35.715, 139.774, 60, 5, 23));

        List<String> names = planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, false).stream()
                .map(i -> pois.get(i).getName())
                .toList();

        assertEquals("Bar", names.getLast());
    }

    /**
     * No stop may be scheduled to run past its closing time when a feasible order exists. Waiting for
     * a venue to open is a different matter — with an evening-only stop in the list it is unavoidable,
     * and the planner is allowed to trade waiting for feasibility.
     */
    @Test
    void producesAScheduleWithNoClosingViolations() {
        List<Poi> pois = List.of(
                poi("Bar", 35.694, 139.705, 90, 18, 24),
                poi("Shrine", 35.715, 139.797, 60, 6, 17),
                poi("Museum", 35.719, 139.777, 120, 9, 17),
                poi("Tower", 35.659, 139.745, 75, 9, 22),
                poi("Park", 35.715, 139.774, 60, 5, 23));

        List<Integer> order = planner.optimizeOrder(pois, TravelMode.TRANSIT, 9, false);
        List<Poi> ordered = order.stream().map(pois::get).toList();
        RoutePlanner.DayPlan plan = planner.buildDay(ordered, TravelMode.TRANSIT, 9, 21);

        List<String> violations = plan.stops().stream()
                .flatMap(s -> s.warnings().stream())
                .map(NoticeDto::code)
                .filter(RoutePlanner.CLOSES_EARLY::equals)
                .toList();
        assertEquals(List.of(), violations, "planner scheduled a stop past closing time");
    }

    /** Optimizing must never regress the documented closed/finish/travel objective. */
    @Test
    void neverRegressesTheDocumentedLexicographicObjective() {
        List<Poi> pois = List.of(
                poi("Ghibli", 35.6962, 139.5704, 120, 10, 18),
                poi("Senso-ji", 35.7148, 139.7967, 60, 6, 17),
                poi("Shibuya", 35.6580, 139.7016, 60, 10, 22),
                poi("teamLab", 35.6487, 139.7864, 120, 9, 21),
                poi("Meiji", 35.6764, 139.6993, 60, 5, 18),
                poi("Skytree", 35.7101, 139.8107, 90, 9, 21));

        RoutePlanner.OptimizationResult result = planner.optimizeDetailed(
                pois, TravelMode.TRANSIT, 9, Collections.nCopies(pois.size(), false));
        RoutePlanner.RouteObjective before = result.before();
        RoutePlanner.RouteObjective after = result.after();
        boolean noWorse = after.closedMinutes() < before.closedMinutes()
                || (after.closedMinutes() == before.closedMinutes()
                && (after.finishMinutes() < before.finishMinutes()
                || (after.finishMinutes() == before.finishMinutes()
                && after.travelMinutes() <= before.travelMinutes())));

        assertTrue(noWorse, "objective regressed: " + before + " -> " + after);
    }

    @Test
    void keepsThePinnedFirstStopInPlace() {
        List<Poi> pois = List.of(
                poi("Hotel", 35.6900, 139.7000, 15, 0, 24),
                poi("Far", 35.7500, 139.8500, 60, 0, 24),
                poi("Near", 35.6910, 139.7020, 60, 0, 24),
                poi("Mid", 35.7100, 139.7800, 60, 0, 24));

        List<Integer> order = planner.optimizeOrder(pois, TravelMode.DRIVE, 9, true);
        assertEquals(0, order.getFirst(), "pinned first stop moved");
    }
}
