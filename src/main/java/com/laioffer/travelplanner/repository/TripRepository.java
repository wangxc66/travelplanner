package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    interface TripSummaryView {
        Long getId();
        String getTitle();
        String getCityName();
        String getHeroEmoji();
        LocalDate getStartDate();
        int getNumDays();
        long getItemCount();
    }

    List<Trip> findByUserIdOrderByIdDesc(Long userId);

    @EntityGraph(attributePaths = "city")
    Optional<Trip> findByIdAndUserId(Long id, Long userId);

    /** Serializes all writes to one trip while also enforcing ownership in the same SQL query. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id and t.user.id = :userId")
    Optional<Trip> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** One grouped query replaces trip + city + one item-count query per row. */
    @Query("""
            select t.id as id, t.title as title, c.name as cityName,
                   c.heroEmoji as heroEmoji, t.startDate as startDate,
                   t.numDays as numDays, count(i.id) as itemCount
            from Trip t
            join t.city c
            left join t.items i
            where t.user.id = :userId
            group by t.id, t.title, c.name, c.heroEmoji, t.startDate, t.numDays
            order by t.id desc
            """)
    List<TripSummaryView> findSummariesByUserId(@Param("userId") Long userId);
}
