package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    List<Trip> findByUserIdOrderByIdDesc(Long userId);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);
}
