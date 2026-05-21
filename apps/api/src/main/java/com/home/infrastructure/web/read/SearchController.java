package com.home.infrastructure.web.read;

import java.util.List;

import com.home.application.read.MvpReadUseCase;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

	private final MvpReadUseCase readUseCase;

	public SearchController(MvpReadUseCase readUseCase) {
		this.readUseCase = readUseCase;
	}

	@GetMapping("/api/v1/search/complexes")
	public ResponseEntity<List<SearchComplexResponse>> searchComplexes(@RequestParam("q") String query) {
		return ResponseEntity.ok(readUseCase.searchComplexes(query.trim()));
	}
}
