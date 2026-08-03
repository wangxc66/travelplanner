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
     */
    @Query("""
            select p from Poi p
            where p.city.id = :cityId
              and (:keyword = '' or lower(p.name) like concat('%', :keyword, '%')
                                 or lower(p.category) like concat('%', :keyword, '%')
                                 or lower(p.description) like concat('%', :keyword, '%'))
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
