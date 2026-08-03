package com.laioffer.travelplanner.service;

/**
 * One hop between two consecutive stops.
 *
 * @param minutes  door-to-door travel time
 * @param km       distance actually covered
 * @param polyline Google-encoded geometry of the real route, or {@code null} when the leg came from
 *                 the offline estimator — in which case the client draws a straight line.
 */
public record TravelLeg(int minutes, double km, String polyline) {

    public static TravelLeg none() {
        return new TravelLeg(0, 0, null);
    }
}
