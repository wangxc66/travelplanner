package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import org.springframework.stereotype.Service;

/**
 * Estimates leg distance and duration between two POIs.
 *
 * <p>The MVP derives duration from great-circle distance plus a per-mode detour factor and fixed
 * overhead (transit waiting, parking). That keeps the whole planner runnable with no API key and no
 * quota. Replacing this single class with a Google Distance Matrix client — cached under
 * {@code CacheConfig.TRAVEL_MATRIX} — upgrades every route in the product without touching the
 * optimizer.
 */
@Service
public class TravelTimeEstimator {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public double distanceKm(Poi from, Poi to) {
        return distanceKm(from.getLat(), from.getLng(), to.getLat(), to.getLng());
    }

    /** Road/route distance approximation, i.e. what a traveler actually covers. */
    public double routeKm(Poi from, Poi to, TravelMode mode) {
        return distanceKm(from, to) * mode.getDetourFactor();
    }

    public int minutes(Poi from, Poi to, TravelMode mode) {
        return minutesForKm(routeKm(from, to, mode), mode);
    }

    /**
     * Duration for a distance that is already the real routed distance — so no detour factor is
     * applied on top. Used when a routing engine gives us true road length but no usable duration for
     * the mode (public OSRM has no transit, and its pedestrian profile returns car-like times).
     */
    public int minutesForKm(double km, TravelMode mode) {
        if (km < 0.05) {
            return 0;
        }
        return (int) Math.round(km / mode.getSpeedKmh() * 60) + mode.getOverheadMinutes();
    }
}
