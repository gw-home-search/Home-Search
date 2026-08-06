package com.home.infrastructure.web.seo;

import com.home.application.seo.SeoIndexMode;
import com.home.application.seo.SeoQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class SeoController {
    private final SeoQueryService service;

    public SeoController(SeoQueryService service) {
        this.service = service;
    }

    @GetMapping("/internal/v1/seo/complexes/{complexId}")
    public SeoComplexResponse complex(@PathVariable @Positive long complexId) {
        return SeoComplexResponse.from(service.getComplex(complexId));
    }

    @GetMapping("/internal/v1/seo/regions/{regionId}")
    public SeoRegionResponse region(
            @PathVariable @Positive long regionId, @RequestParam(defaultValue = "PILOT") SeoIndexMode mode) {
        return SeoRegionResponse.from(service.getRegion(regionId, mode));
    }

    @GetMapping("/internal/v1/seo/catalog/complexes")
    public List<SeoCatalogResponses.Complex> complexes(
            @RequestParam(defaultValue = "PILOT") SeoIndexMode mode,
            @RequestParam(defaultValue = "0") @Min(0) long afterId,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(10_000) int limit) {
        return service.getComplexCatalog(mode, afterId, limit).stream()
                .map(item -> new SeoCatalogResponses.Complex(item.complexId()))
                .toList();
    }

    @GetMapping("/internal/v1/seo/catalog/regions")
    public List<SeoCatalogResponses.Region> regions(@RequestParam(defaultValue = "PILOT") SeoIndexMode mode) {
        return service.getRegionCatalog(mode).stream()
                .map(item -> new SeoCatalogResponses.Region(item.regionId()))
                .toList();
    }
}
