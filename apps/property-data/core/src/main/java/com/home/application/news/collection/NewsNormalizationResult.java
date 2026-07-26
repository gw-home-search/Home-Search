package com.home.application.news.collection;

import com.home.domain.news.NewsRejectionReason;
import java.util.Objects;

public record NewsNormalizationResult(NormalizedNewsItem item, NewsRejectionReason rejectionReason) {

    public NewsNormalizationResult {
        if ((item == null) == (rejectionReason == null)) {
            throw new IllegalArgumentException("exactly one normalization outcome is required");
        }
    }

    public static NewsNormalizationResult accepted(NormalizedNewsItem item) {
        return new NewsNormalizationResult(Objects.requireNonNull(item), null);
    }

    public static NewsNormalizationResult rejected(NewsRejectionReason reason) {
        return new NewsNormalizationResult(null, Objects.requireNonNull(reason));
    }

    public boolean accepted() {
        return item != null;
    }
}
