package com.laioffer.travelplanner.entity;

/**
 * Travel mode for a leg between two POIs.
 *
 * <p>{@code speedKmh} / {@code detourFactor} / {@code overheadMinutes} let us estimate realistic
 * city travel time from straight-line distance alone, so the planner works with zero external
 * API quota. Swapping in the Google Distance Matrix later only means replacing
 * {@code TravelTimeEstimator}, not the planner.
 */
public enum TravelMode {
    WALK(4.6, 1.25, 1),
    TRANSIT(19.0, 1.30, 7),
    DRIVE(27.0, 1.35, 4);

    private final double speedKmh;
    private final double detourFactor;
    private final int overheadMinutes;

    TravelMode(double speedKmh, double detourFactor, int overheadMinutes) {
        this.speedKmh = speedKmh;
        this.detourFactor = detourFactor;
        this.overheadMinutes = overheadMinutes;
    }

    public double getSpeedKmh() {
        return speedKmh;
    }

    public double getDetourFactor() {
        return detourFactor;
    }

    public int getOverheadMinutes() {
        return overheadMinutes;
    }
}
