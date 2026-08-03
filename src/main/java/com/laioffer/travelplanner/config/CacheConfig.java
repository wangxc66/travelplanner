package com.laioffer.travelplanner.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The POI catalog is read-heavy and effectively static, and the travel matrix for a given set of
 * coordinates never changes. Caching both keeps "optimize" instant and, once a real Distance Matrix
 * provider is plugged in, keeps the API bill flat.
 */
@Configuration
public class CacheConfig {

    public static final String POI_SEARCH = "poiSearch";
    public static final String CITIES = "cities";
    public static final String TRAVEL_MATRIX = "travelMatrix";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(POI_SEARCH, CITIES, TRAVEL_MATRIX);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofHours(6)));
        return manager;
    }
}
