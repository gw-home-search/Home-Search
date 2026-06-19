package com.home.news.application;

public interface NewsMetadataClient {

	NewsSearchResult search(String queryText, int display, String sortOrder);
}
