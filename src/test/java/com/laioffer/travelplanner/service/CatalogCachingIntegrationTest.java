package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.config.CacheConfig;
import com.laioffer.travelplanner.repository.CityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travelplanner.h2.tcp.enabled=false",
        "travelplanner.osrm.enabled=false"
})
class CatalogCachingIntegrationTest {

    @Autowired private CatalogService catalogService;
    @Autowired private CityRepository cityRepository;
    @Autowired private CacheManager cacheManager;

    private Long cityId;

    @BeforeEach
    void clearCatalogCaches() {
        cityId = cityRepository.findAllByOrderByNameAsc().getFirst().getId();
        clear(CacheConfig.CITIES);
        clear(CacheConfig.CATEGORIES);
        clear(CacheConfig.POI_SEARCH);
    }

    @Test
    void normalizedSearchesShareOneCacheEntry() {
        var first = catalogService.searchPois(cityId, " Museum ", " ALL ", 60);
        var second = catalogService.searchPois(cityId, "museum", "", 60);

        assertThat(second).isEqualTo(first);
        assertThat(estimatedSize(CacheConfig.POI_SEARCH)).isEqualTo(1);
    }

    @Test
    void citiesAndCategoriesAreCachedIndependently() {
        catalogService.cities();
        catalogService.cities();
        catalogService.categories(cityId);
        catalogService.categories(cityId);

        assertThat(estimatedSize(CacheConfig.CITIES)).isEqualTo(1);
        assertThat(estimatedSize(CacheConfig.CATEGORIES)).isEqualTo(1);
    }

    @Test
    void warmSearchMeetsInteractiveResponseTimeTarget() {
        catalogService.searchPois(cityId, "", "", 60);
        for (int i = 0; i < 100; i++) {
            catalogService.searchPois(cityId, "", "", 60);
        }

        long started = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            catalogService.searchPois(cityId, "", "", 60);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofMillis(250));
    }

    private void clear(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).isNotNull();
        cache.clear();
    }

    private long estimatedSize(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).isNotNull();
        com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) cache.getNativeCache();
        return nativeCache.estimatedSize();
    }
}
