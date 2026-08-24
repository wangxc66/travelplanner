package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findByTripIdOrderByDayIndexAscSeqAsc(Long tripId);

    List<ItineraryItem> findByTripIdAndDayIndexOrderBySeqAsc(Long tripId, int dayIndex);

    boolean existsByTripIdAndPoiId(Long tripId, Long poiId);

    long countByTripIdAndDayIndex(Long tripId, int dayIndex);

    Optional<ItineraryItem> findByIdAndTripId(Long id, Long tripId);
}
