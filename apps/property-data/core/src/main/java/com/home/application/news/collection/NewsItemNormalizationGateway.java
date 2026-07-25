package com.home.application.news.collection;

public interface NewsItemNormalizationGateway {

    NewsNormalizationResult tryNormalize(NewsProviderItem raw);
}
