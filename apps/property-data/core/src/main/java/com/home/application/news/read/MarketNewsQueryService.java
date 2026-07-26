package com.home.application.news.read;

import com.home.application.read.ResourceNotFoundException;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsScopeType;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarketNewsQueryService {

    private static final int COMPLEX_NEWS_LIMIT = 5;
    private final MarketNewsReadRepository repository;

    public MarketNewsQueryService(MarketNewsReadRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional(readOnly = true)
    public MarketNewsReadResult list(
            MarketNewsScopeType scopeType, String regionCode, MarketNewsCategory category, String cursor, int limit) {
        validate(scopeType, regionCode, limit);
        MarketNewsCategory resolvedCategory = category == null ? MarketNewsCategory.ALL : category;
        MarketNewsCursor decodedCursor = MarketNewsCursor.decode(cursor);
        return repository
                .findPublished(scopeType, regionCode, resolvedCategory, decodedCursor, limit)
                .orElseGet(() -> MarketNewsReadResult.unavailable(scopeType, regionCode, resolvedCategory));
    }

    @Transactional(readOnly = true)
    public List<MarketNewsItemView> complexNews(long complexId) {
        if (complexId <= 0) {
            throw new InvalidNewsQueryException("complexId는 양수여야 합니다");
        }
        if (!repository.existsComplex(complexId)) {
            throw new ResourceNotFoundException("Complex not found: " + complexId);
        }
        return repository.findComplexNews(complexId, COMPLEX_NEWS_LIMIT);
    }

    private void validate(MarketNewsScopeType scopeType, String regionCode, int limit) {
        Objects.requireNonNull(scopeType, "scopeType is required");
        if (limit < 1 || limit > 50) {
            throw new InvalidNewsQueryException("limit은 1부터 50까지 허용됩니다");
        }
        boolean hasRegion = regionCode != null && !regionCode.isBlank();
        if ((scopeType == MarketNewsScopeType.SIDO) != hasRegion) {
            throw new InvalidNewsQueryException("regionCode는 SIDO scope에서만 필수입니다");
        }
        if (scopeType == MarketNewsScopeType.SIDO && !repository.existsRootSidoCode(regionCode)) {
            throw new InvalidNewsQueryException("regionCode는 존재하는 root SIDO여야 합니다");
        }
    }
}
