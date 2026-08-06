package com.home.application.seo;

import java.util.List;

public record SeoRegionResult(
        Long regionId,
        String name,
        boolean indexable,
        long indexableComplexCount,
        List<SeoComplexResult.Breadcrumb> breadcrumbs,
        List<RepresentativeComplex> representativeComplexes) {

    public record RepresentativeComplex(Long complexId, String name, String address) {}
}
