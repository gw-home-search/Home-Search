package com.home.application.seo;

import java.util.List;
import java.util.Optional;

public interface SeoReader {
    Optional<SeoComplexResult> findComplex(long complexId);

    Optional<SeoRegionResult> findRegion(long regionId);

    List<SeoCatalogComplex> findComplexCatalog(SeoIndexMode mode, long afterId, int limit);

    List<SeoCatalogRegion> findRegionCatalog(SeoIndexMode mode);
}
