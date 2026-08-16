package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.config.CacheConfig;
import com.laioffer.travelplanner.dto.Dtos.CityDto;
import com.laioffer.travelplanner.dto.Dtos.PoiDto;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.repository.CityRepository;
import com.laioffer.travelplanner.repository.PoiRepository;
import com.laioffer.travelplanner.web.ApiException;
import org.springframework.cache.annotation.Cacheable;
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
        return cityRepository.findAllByOrderByNameAsc().stream()
                .map(c -> new CityDto(c.getId(), c.getName(), c.getCountry(), c.getLat(), c.getLng(),
                        c.getDefaultZoom(), c.getHeroEmoji(), poiRepository.countByCityId(c.getId())))
                .toList();
    }

    @Cacheable(value = CacheConfig.POI_SEARCH, key = "#cityId + '|' + #keyword + '|' + #category + '|' + #limit")
    @Transactional(readOnly = true)
    public List<PoiDto> searchPois(Long cityId, String keyword, String category, int limit) {
        String k = keyword == null ? "" : escapeLike(keyword.trim().toLowerCase(Locale.ROOT));
        String c = category == null || category.isBlank() || "All".equalsIgnoreCase(category) ? "" : category;
        return poiRepository.search(cityId, k, c).stream()
                .limit(limit)
                .map(CatalogService::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> categories(Long cityId) {
        return poiRepository.findCategories(cityId);
    }

    /**
     * Makes {@code %}, {@code _} and {@code \} literal inside a LIKE pattern.
     *
     * <p>Without this a traveler searching for {@code %} gets back the entire city, because the
     * character they typed is the SQL wildcard. Paired with the {@code escape} clause in
     * {@link com.laioffer.travelplanner.repository.PoiRepository#search}, a keyword now only ever
     * means the text the traveler typed.
     */
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
