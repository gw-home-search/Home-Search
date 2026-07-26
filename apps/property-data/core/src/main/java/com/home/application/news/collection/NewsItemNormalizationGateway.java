package com.home.application.news.collection;

import java.time.Instant;
import java.util.Optional;

public interface NewsItemNormalizationGateway {

    NewsNormalizationResult tryNormalize(NewsProviderItem raw);

    Optional<Instant> tryParseProvidedAt(NewsProviderItem raw);
}
