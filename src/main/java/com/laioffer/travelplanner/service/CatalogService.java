package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.config.CacheConfig;
import com.laioffer.travelplanner.dto.Dtos.CityDto;
import com.laioffer.travelplanner.dto.Dtos.PoiDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.repository.CityRepository;
import com.laioffer.travelplanner.repository.PoiRepository;
import com.laioffer.travelplanner.web.ApiException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class CatalogService {

    private final CityRepository cityRepository;
    private final PoiRepository poiRepository;

    public CatalogService(CityRepository cityRepository, PoiRepository poiRepository) {
        this.cityRepository = cityRepository;
        this.poiRepository = poiRepository;
    }

    @Cacheable(CacheConfig.CITIES)
    @Transactional(readOnly = true)
    public List<CityDto> cities() {
        return cityRepository.findCatalog().stream()
                .map(c -> new CityDto(c.getId(), c.getName(), c.getCountry(), c.getLat(), c.getLng(),
                        c.getDefaultZoom(), c.getHeroEmoji(), c.getPoiCount()))
                .toList();
    }

    @Cacheable(value = CacheConfig.POI_SEARCH,
            key = "T(com.laioffer.travelplanner.service.CatalogService).searchCacheKey(#cityId, #keyword, #category, #limit)")
    @Transactional(readOnly = true)
    public List<PoiDto> searchPois(Long cityId, String keyword, String category, int limit) {
        String k = normalizeKeyword(keyword);
        String c = normalizeCategory(category);
        int boundedLimit = Math.clamp(limit, 1, 200);
        return poiRepository.search(cityId, k, c, PageRequest.of(0, boundedLimit)).stream()
                // Defensive ceiling for alternate repository implementations and test doubles.
                .limit(boundedLimit)
                .map(CatalogService::toDto)
                .toList();
    }

    @Cacheable(value = CacheConfig.CATEGORIES, key = "#cityId")
    @Transactional(readOnly = true)
    public List<String> categories(Long cityId) {
        return poiRepository.findCategories(cityId);
    }

    /** Canonical cache identity: inputs with identical search semantics share one entry. */
    public static String searchCacheKey(Long cityId, String keyword, String category, int limit) {
        return cityId + "|" + normalizeKeyword(keyword) + "|"
                + normalizeCategory(category).toLowerCase(Locale.ROOT) + "|" + Math.clamp(limit, 1, 200);
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : escapeLike(keyword.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return "";
        }
        String normalized = category.trim();
        return normalized.isEmpty() || "All".equalsIgnoreCase(normalized) ? "" : normalized;
    }

    /** Treat LIKE metacharacters typed by a traveler as literal searchable text. */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static PoiDto toDto(Poi poi) {
        return new PoiDto(poi.getId(), poi.getName(), poi.getCategory(), poi.getLat(), poi.getLng(),
                poi.getRating(), poi.getAvgVisitMinutes(), openLabel(poi), poi.isAlwaysOpen(),
                poi.getDescription());
    }

    /** Clock range only — digits read the same in every language. Null when the place never closes. */
    private static String openLabel(Poi poi) {
        if (poi.isAlwaysOpen()) {
            return null;
        }
        return RoutePlanner.fmt(poi.getOpenHour() * 60) + " – " + RoutePlanner.fmt(poi.getCloseHour() * 60);
    }
}
