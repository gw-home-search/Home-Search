package com.home.application.seo;

import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.ResourceNotFoundException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SeoQueryService {
    private static final int MAX_CATALOG_PAGE_SIZE = 10_000;
    private final SeoReader reader;

    public SeoQueryService(SeoReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public SeoComplexResult getComplex(long complexId) {
        return reader.findComplex(complexId)
                .orElseThrow(() -> new ResourceNotFoundException("SEO complex not found: " + complexId));
    }

    public SeoRegionResult getRegion(long regionId) {
        return reader.findRegion(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("SEO region not found: " + regionId));
    }

    public List<SeoCatalogComplex> getComplexCatalog(SeoIndexMode mode, long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > MAX_CATALOG_PAGE_SIZE) {
            throw new InvalidReadRequestException("invalid SEO catalog window");
        }
        return reader.findComplexCatalog(Objects.requireNonNull(mode), afterId, limit);
    }

    public List<SeoCatalogRegion> getRegionCatalog(SeoIndexMode mode) {
        return reader.findRegionCatalog(Objects.requireNonNull(mode));
    }
}
