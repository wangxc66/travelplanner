package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.City;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, Long> {

    interface CityCatalogView {
        Long getId();
        String getName();
        String getCountry();
        double getLat();
        double getLng();
        int getDefaultZoom();
        String getHeroEmoji();
        long getPoiCount();
    }

    List<City> findAllByOrderByNameAsc();

    /** Fetches every city and its POI count in one deterministic query. */
    @Query("""
            select c.id as id, c.name as name, c.country as country,
                   c.lat as lat, c.lng as lng, c.defaultZoom as defaultZoom,
                   c.heroEmoji as heroEmoji, count(p.id) as poiCount
            from City c
            left join Poi p on p.city = c
            group by c.id, c.name, c.country, c.lat, c.lng, c.defaultZoom, c.heroEmoji
            order by c.name asc, c.id asc
            """)
    List<CityCatalogView> findCatalog();

    Optional<City> findByNameAndCountry(String name, String country);
}
