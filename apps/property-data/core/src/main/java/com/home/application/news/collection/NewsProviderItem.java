package com.home.application.news.collection;

public record NewsProviderItem(
        String title,
        String originalLink,
        String link,
        String description,
        String pubDate,
        int providerStart,
        int providerRank) {}
