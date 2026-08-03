package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;

import java.util.List;

/**
 * Everything the planner needs to know about moving between places.
 *
 * <p>Two implementations: {@link EstimatedRouteProvider} works offline from coordinates alone, and
 * {@link GoogleRoutesProvider} asks the Google Routes API for real road, transit and walking routes.
 * The optimizer and the timeline are written against this interface, so switching between them
 * changes route quality without changing a line of planning logic.
 */
public interface RouteProvider {

    /** Symmetric-ish travel time matrix in minutes, used by the day optimizer. */
    int[][] matrix(List<Poi> pois, TravelMode mode);

    /** Legs for an already-ordered day. Returns {@code pois.size() - 1} entries, or empty. */
    List<TravelLeg> legs(List<Poi> ordered, TravelMode mode);

    /** True when the numbers and geometry come from a real routing engine. */
    boolean isReal();
}
