package com.laioffer.travelplanner.web;

import com.laioffer.travelplanner.dto.Dtos.CityDto;
import com.laioffer.travelplanner.dto.Dtos.PoiDto;
import com.laioffer.travelplanner.service.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/cities")
    public List<CityDto> cities() {
        return catalogService.cities();
    }

    @GetMapping("/cities/{cityId}/pois")
    public List<PoiDto> pois(@PathVariable Long cityId,
                             @RequestParam(defaultValue = "") String keyword,
                             @RequestParam(defaultValue = "") String category,
                             @RequestParam(defaultValue = "60") int limit) {
        return catalogService.searchPois(cityId, keyword, category, Math.clamp(limit, 1, 200));
    }

    @GetMapping("/cities/{cityId}/categories")
    public List<String> categories(@PathVariable Long cityId) {
        return catalogService.categories(cityId);
    }
}
