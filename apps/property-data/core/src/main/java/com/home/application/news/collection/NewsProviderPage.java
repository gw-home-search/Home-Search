package com.home.application.news.collection;

import java.util.List;

public record NewsProviderPage(int total, int start, int display, List<NewsProviderItem> items) {}
