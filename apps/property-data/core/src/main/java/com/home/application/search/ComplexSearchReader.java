package com.home.application.search;

import java.util.List;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.SearchComplexResult;

public interface ComplexSearchReader {

	List<SearchComplexResult> searchComplexes(String query);

	List<ComplexSuggestionResult> suggestComplexes(String query, int limit);
}
