package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.entity.Poi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PoiRepository extends JpaRepository<Poi, Long> {

    /**
     * Keyword + category search inside one city. Empty string means "no filter", which keeps the
     * query plan simple and avoids null-typing headaches across H2 and PostgreSQL.
     *
     * <p>{@code :keyword} arrives already trimmed, lower-cased and LIKE-escaped by the caller — see
     * {@code CatalogService.escapeLike}. The {@code escape} clause is what makes that escaping
     * effective: without it a keyword of {@code %} is a wildcard that matches every POI in the city.
     *
     * <p>The sort is a total order. {@code rating, name} alone leaves rows tied whenever the catalog
     * holds two places with the same score and title, and a tie is resolved by whatever the database
     * feels like returning — which is not the same on H2 and PostgreSQL. {@code p.id} breaks it, so
     * the same query answers the same way everywhere, every time.
     */
    @Query("""
            select p from Poi p
            where p.city.id = :cityId
              and (:keyword = '' or lower(p.name) like concat('%', :keyword, '%') escape '\\'
                                 or lower(p.category) like concat('%', :keyword, '%') escape '\\'
                                 or lower(p.description) like concat('%', :keyword, '%') escape '\\')
              and (:category = '' or p.category = :category)
            order by p.rating desc, p.name asc
            """)
    List<Poi> search(@Param("cityId") Long cityId,
                     @Param("keyword") String keyword,
                     @Param("category") String category);

    @Query("select distinct p.category from Poi p where p.city.id = :cityId order by p.category")
    List<String> findCategories(@Param("cityId") Long cityId);

    long countByCityId(Long cityId);
}
