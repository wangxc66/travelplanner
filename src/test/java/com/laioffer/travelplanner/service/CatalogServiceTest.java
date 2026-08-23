package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.CatalogFixtures;
import com.laioffer.travelplanner.dto.Dtos.PoiDto;
import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.repository.CityRepository;
import com.laioffer.travelplanner.repository.PoiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the service does to a request before the database ever sees it: trimming, case folding,
 * LIKE escaping, the "no filter" sentinel, the result ceiling — plus the shape of the DTO that
 * comes back out. No Spring context: these are pure decisions, and they should fail in milliseconds.
 */
class CatalogServiceTest {

    private static final String EN_DASH = "–";

    private PoiRepository poiRepository;
    private CatalogService catalogService;
    private City city;

    @BeforeEach
    void setUp() {
        poiRepository = mock(PoiRepository.class);
        catalogService = new CatalogService(mock(CityRepository.class), poiRepository);
        city = CatalogFixtures.testville();
        when(poiRepository.search(anyLong(), anyString(), anyString(), any(Pageable.class))).thenReturn(List.of());
    }

    /** The keyword the repository actually received for a given user input. */
    private String keywordFor(String userInput) {
        catalogService.searchPois(1L, userInput, "", 60);
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(poiRepository, atLeastOnce()).search(anyLong(), keyword.capture(), anyString(), any(Pageable.class));
        return keyword.getAllValues().getLast();
    }

    /** The category the repository actually received for a given user input. */
    private String categoryFor(String userInput) {
        catalogService.searchPois(1L, "", userInput, 60);
        ArgumentCaptor<String> category = ArgumentCaptor.forClass(String.class);
        verify(poiRepository, atLeastOnce()).search(anyLong(), anyString(), category.capture(), any(Pageable.class));
        return category.getAllValues().getLast();
    }

    @Test
    @DisplayName("keywords are trimmed and lower-cased")
    void normalisesKeyword() {
        assertThat(keywordFor("  Espresso  ")).isEqualTo("espresso");
    }

    @Test
    @DisplayName("a null or blank keyword becomes the no-filter sentinel")
    void treatsBlankKeywordAsNoFilter() {
        assertThat(keywordFor(null)).isEmpty();
        assertThat(keywordFor("   ")).isEmpty();
    }

    @Test
    @DisplayName("LIKE wildcards in a keyword are escaped into literals")
    void escapesLikeWildcards() {
        assertThat(keywordFor("50%")).isEqualTo("50\\%");
        assertThat(keywordFor("sushi_bar")).isEqualTo("sushi\\_bar");
        assertThat(keywordFor("back\\slash")).isEqualTo("back\\\\slash");
    }

    @Test
    @DisplayName("a null, blank or \"All\" category becomes the no-filter sentinel")
    void treatsAllAsNoFilter() {
        assertThat(categoryFor(null)).isEmpty();
        assertThat(categoryFor("  ")).isEmpty();
        assertThat(categoryFor("All")).isEmpty();
        assertThat(categoryFor("all")).isEmpty();
    }

    @Test
    @DisplayName("a real category is passed through untouched; the database folds the case")
    void passesCategoryThrough() {
        assertThat(categoryFor("Food")).isEqualTo("Food");
        assertThat(categoryFor("food")).isEqualTo("food");
        assertThat(categoryFor("  Food  ")).isEqualTo("Food");
    }

    @Test
    @DisplayName("semantically equivalent searches have one canonical cache identity")
    void normalizesSearchCacheKey() {
        assertThat(CatalogService.searchCacheKey(1L, " Temple ", " FOOD ", 60))
                .isEqualTo(CatalogService.searchCacheKey(1L, "temple", "food", 60));
        assertThat(CatalogService.searchCacheKey(1L, "", "All", 0))
                .isEqualTo(CatalogService.searchCacheKey(1L, null, null, 1));
        assertThat(CatalogService.searchCacheKey(1L, "", "", 999))
                .endsWith("|200");
    }

    @Test
    @DisplayName("service-level callers receive the same bounded limit contract as HTTP callers")
    void clampsLimitBeforeBuildingPageRequest() {
        catalogService.searchPois(1L, "", "", 0);
        catalogService.searchPois(1L, "", "", 999);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(poiRepository, org.mockito.Mockito.times(2))
                .search(anyLong(), anyString(), anyString(), pageable.capture());
        assertThat(pageable.getAllValues()).extracting(Pageable::getPageSize)
                .containsExactly(1, 200);
    }

    @Test
    @DisplayName("no search returns more rows than the caller asked for")
    void boundsResultCount() {
        when(poiRepository.search(anyLong(), any(), any(), any(Pageable.class)))
                .thenReturn(CatalogFixtures.testvillePois(city));

        assertThat(catalogService.searchPois(1L, "", "", 3)).hasSize(3);
        assertThat(catalogService.searchPois(1L, "", "", 60)).hasSize(8);
    }

    @Test
    @DisplayName("the result order is the repository's order, untouched")
    void preservesRepositoryOrder() {
        when(poiRepository.search(anyLong(), any(), any(), any(Pageable.class)))
                .thenReturn(CatalogFixtures.testvillePois(city));

        assertThat(catalogService.searchPois(1L, "", "", 60))
                .extracting(PoiDto::name)
                .startsWith("Beta Park", "Alpha Museum", "Alpha Museum");
    }

    @Test
    @DisplayName("an always-open place has no opening label; the client supplies that wording")
    void mapsAlwaysOpenToNullLabel() {
        PoiDto dto = CatalogService.toDto(poi(0, 24));

        assertThat(dto.alwaysOpen()).isTrue();
        assertThat(dto.openLabel()).isNull();
    }

    @Test
    @DisplayName("a normal opening window becomes a language-neutral clock range")
    void mapsOpeningWindowToClockRange() {
        assertThat(CatalogService.toDto(poi(9, 18)).openLabel()).isEqualTo("09:00 " + EN_DASH + " 18:00");
        assertThat(CatalogService.toDto(poi(6, 12)).openLabel()).isEqualTo("06:00 " + EN_DASH + " 12:00");
    }

    @Test
    @DisplayName("closing at hour 24 renders as next-day midnight, not as 24:00")
    void mapsMidnightCloseAsNextDay() {
        // Pins existing behaviour rather than blessing it: see the known limitation in
        // docs/CATALOG_BEHAVIOR.md. A place open 09:00-24:00 is not "always open".
        PoiDto dto = CatalogService.toDto(poi(9, 24));

        assertThat(dto.alwaysOpen()).isFalse();
        assertThat(dto.openLabel()).isEqualTo("09:00 " + EN_DASH + " 00:00 (+1d)");
    }

    @Test
    @DisplayName("every catalog field survives the mapping")
    void mapsAllFields() {
        Poi poi = new Poi(city, "Gamma Cafe", "Food", 10.4, 20.4, 4.2, 30, 8, 20, "Serves espresso");

        PoiDto dto = CatalogService.toDto(poi);

        assertThat(dto.name()).isEqualTo("Gamma Cafe");
        assertThat(dto.category()).isEqualTo("Food");
        assertThat(dto.lat()).isEqualTo(10.4);
        assertThat(dto.lng()).isEqualTo(20.4);
        assertThat(dto.rating()).isEqualTo(4.2);
        assertThat(dto.avgVisitMinutes()).isEqualTo(30);
        assertThat(dto.description()).isEqualTo("Serves espresso");
    }

    private Poi poi(int openHour, int closeHour) {
        return new Poi(city, "Somewhere", "Landmark", 10.0, 20.0, 4.0, 60, openHour, closeHour, "A place");
    }
}
