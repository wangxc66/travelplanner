package com.laioffer.travelplanner.repository;

import com.laioffer.travelplanner.CatalogFixtures;
import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules in {@code docs/CATALOG_BEHAVIOR.md} that only a real database can prove: ordering,
 * case-folding and wildcard escaping all live in SQL, not in Java.
 *
 * <p>Every assertion scopes itself to the fixture city, so the test says the same thing whether or
 * not the demo seed happens to be present.
 *
 * <p>{@code @SpringBootTest} rather than {@code @DataJpaTest}: Spring Boot 4 moved the JPA test slice
 * into a module this project does not depend on, and a repository test is not worth a build-file
 * change. {@code @Transactional} rolls each test back, so the fixtures never leak between tests.
 */
@SpringBootTest(properties = "travelplanner.h2.tcp.enabled=false")
@Transactional
class PoiRepositoryTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private PoiRepository poiRepository;

    private Long cityId;

    @BeforeEach
    void seedFixtures() {
        City testville = cityRepository.save(CatalogFixtures.testville());
        City otherville = cityRepository.save(CatalogFixtures.otherville());
        poiRepository.saveAll(CatalogFixtures.testvillePois(testville));
        poiRepository.save(CatalogFixtures.othervillePoi(otherville));
        cityId = testville.getId();
    }

    private List<Poi> search(String keyword, String category) {
        return poiRepository.search(cityId, keyword, category);
    }

    private List<String> names(List<Poi> pois) {
        return pois.stream().map(Poi::getName).toList();
    }

    @Test
    @DisplayName("an unfiltered search returns the whole city, best rated first")
    void ordersByRatingThenName() {
        assertThat(names(search("", ""))).containsExactly(
                "Beta Park",
                "Alpha Museum",
                "Alpha Museum",
                "Night Tower",
                "Gamma Cafe",
                "Sushi_Bar Nine",
                "Morning Market",
                "50% Off Outlet");
    }





    @Test
    @DisplayName("keywords match the description, not only the name and category")
    void matchesDescription() {
        assertThat(names(search("espresso", ""))).containsExactly("Gamma Cafe");
    }

    @Test
    @DisplayName("keyword and category narrow each other")
    void combinesKeywordAndCategory() {
        assertThat(names(search("bar", "Food"))).containsExactly("Sushi_Bar Nine");
        assertThat(names(search("bar", "Park"))).isEmpty();
    }

    @Test
    @DisplayName("search never crosses a city boundary")
    void scopesToOneCity() {
        assertThat(names(search("alpha", ""))).containsExactly("Alpha Museum", "Alpha Museum");
        assertThat(search("alpha", "")).allSatisfy(p -> assertThat(p.getCity().getId()).isEqualTo(cityId));
    }

    @Test
    @DisplayName("categories are distinct and alphabetical")
    void listsDistinctCategories() {
        assertThat(poiRepository.findCategories(cityId))
                .containsExactly("Food", "Museum", "Park", "Shopping", "Viewpoint");
    }
}
