package com.home.infrastructure.web.search;

import java.util.List;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.search.ComplexSearchService;
import com.home.infrastructure.web.read.dto.ComplexSuggestionResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

	private final ComplexSearchService searchService;

	public SearchController(ComplexSearchService searchService) {
		this.searchService = searchService;
	}

	@GetMapping("/api/v1/search/complexes")
	public ResponseEntity<List<SearchComplexResponse>> searchComplexes(@RequestParam("q") String query) {
		return ResponseEntity.ok(searchService.searchComplexes(query.trim())
			.stream()
			.map(SearchController::toResponse)
			.toList());
	}

	@GetMapping("/api/v1/search/complexes/suggestions")
	public ResponseEntity<List<ComplexSuggestionResponse>> suggestComplexes(@RequestParam("q") String query) {
		return ResponseEntity.ok(searchService.suggestComplexes(query.trim())
			.stream()
			.map(SearchController::toResponse)
			.toList());
	}

	private static SearchComplexResponse toResponse(SearchComplexResult result) {
		return new SearchComplexResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.latitude(),
			result.longitude(),
			result.address()
		);
	}

	private static ComplexSuggestionResponse toResponse(ComplexSuggestionResult result) {
		return new ComplexSuggestionResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.address()
		);
	}
}
