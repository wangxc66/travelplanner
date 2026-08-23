package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;

import java.util.List;

/**
 * Application-facing contract for itinerary optimization and timeline construction.
 *
 * <p>The contract deliberately uses immutable request/response values. {@link TripService} owns
 * authorization, persistence and transactions; an implementation of this interface owns route
 * computation. This makes the boundary replaceable and straightforward to fake in unit tests.
 */
public interface RoutePlanningService {

    enum Algorithm {
        NO_OP,
        HELD_KARP,
        GREEDY_TWO_OPT
    }

    /** A stop's position is its zero-based slot in the currently persisted day. */
    record OptimizationStop(int position, Poi poi, boolean locked) {
        public OptimizationStop {
            if (position < 0) {
                throw new IllegalArgumentException("position must be non-negative");
            }
            if (poi == null) {
                throw new IllegalArgumentException("poi is required");
            }
        }
    }

    record OptimizationRequest(List<OptimizationStop> stops, TravelMode mode, int dayStartHour) {
        public OptimizationRequest {
            stops = List.copyOf(stops);
            if (mode == null) {
                throw new IllegalArgumentException("mode is required");
            }
            if (dayStartHour < 0 || dayStartHour > 23) {
                throw new IllegalArgumentException("dayStartHour must be between 0 and 23");
            }
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).position() != i) {
                    throw new IllegalArgumentException("stops must have contiguous positions starting at zero");
                }
            }
        }
    }

    /** orderedPositions is a permutation of the request positions; locked positions never move. */
    record OptimizationResult(List<Integer> orderedPositions, Algorithm algorithm) {
        public OptimizationResult {
            orderedPositions = List.copyOf(orderedPositions);
        }
    }

    record StopPlan(int arriveMinutes, int leaveMinutes, int travelMinutesFromPrev,
                    double travelKmFromPrev, String polylineFromPrev, List<NoticeDto> warnings) {
        public StopPlan {
            warnings = List.copyOf(warnings);
        }
    }

    record DayPlan(List<StopPlan> stops, int visitMinutes, int travelMinutes,
                   int startMinutes, int endMinutes, List<NoticeDto> warnings) {
        public DayPlan {
            stops = List.copyOf(stops);
            warnings = List.copyOf(warnings);
        }
    }

    OptimizationResult optimize(OptimizationRequest request);

    DayPlan buildDay(List<Poi> pois, TravelMode mode, int dayStartHour, int dayEndHour);

    static String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h >= 24) {
            return String.format("%02d:%02d (+1d)", h - 24, m);
        }
        return String.format("%02d:%02d", h, m);
    }
}
