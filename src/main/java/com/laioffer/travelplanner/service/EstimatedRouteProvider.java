package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline fallback: great-circle distance with a per-mode detour factor and fixed overhead. Good
 * enough to make the optimizer behave sensibly, and it costs nothing — which is what keeps the app
 * fully demonstrable without a Maps billing account. Produces no geometry, so the map draws straight
 * lines between stops.
 */
public class EstimatedRouteProvider implements RouteProvider {

    private final TravelTimeEstimator estimator;

    public EstimatedRouteProvider(TravelTimeEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public int[][] matrix(List<Poi> pois, TravelMode mode) {
        int n = pois.size();
        int[][] cost = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int minutes = estimator.minutes(pois.get(i), pois.get(j), mode);
                cost[i][j] = minutes;
                cost[j][i] = minutes;
            }
        }
        return cost;
    }

    @Override
    public List<TravelLeg> legs(List<Poi> ordered, TravelMode mode) {
        List<TravelLeg> legs = new ArrayList<>(Math.max(0, ordered.size() - 1));
        for (int i = 1; i < ordered.size(); i++) {
            Poi from = ordered.get(i - 1);
            Poi to = ordered.get(i);
            legs.add(new TravelLeg(estimator.minutes(from, to, mode),
                    round1(estimator.routeKm(from, to, mode)), null));
        }
        return legs;
    }

    @Override
    public boolean isReal() {
        return false;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
