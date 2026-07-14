package com.home.application.search;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.SearchComplexResult;
import java.util.List;

public interface ComplexSearchReader {

    List<SearchComplexResult> searchComplexes(String query);

    List<ComplexSuggestionResult> suggestComplexes(String query, int limit);
}
