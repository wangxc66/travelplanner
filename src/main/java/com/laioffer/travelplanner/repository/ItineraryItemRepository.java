package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findByTripIdOrderByDayIndexAscSeqAsc(Long tripId);

    List<ItineraryItem> findByTripIdAndDayIndexOrderBySeqAsc(Long tripId, int dayIndex);

    boolean existsByTripIdAndPoiId(Long tripId, Long poiId);
}
