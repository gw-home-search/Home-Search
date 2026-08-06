package com.home.infrastructure.web.seo;

import com.home.application.seo.SeoRegionResult;
import java.util.List;

public record SeoRegionResponse(
        Long regionId,
        String name,
        boolean indexable,
        long indexableComplexCount,
        List<SeoComplexResponse.Breadcrumb> breadcrumbs,
        List<RepresentativeComplex> representativeComplexes) {
    static SeoRegionResponse from(SeoRegionResult result) {
        return new SeoRegionResponse(
                result.regionId(),
                result.name(),
                result.indexable(),
                result.indexableComplexCount(),
                result.breadcrumbs().stream()
                        .map(item -> new SeoComplexResponse.Breadcrumb(item.regionId(), item.name()))
                        .toList(),
                result.representativeComplexes().stream()
                        .map(item -> new RepresentativeComplex(item.complexId(), item.name(), item.address()))
                        .toList());
    }

    public record RepresentativeComplex(Long complexId, String name, String address) {}
}
