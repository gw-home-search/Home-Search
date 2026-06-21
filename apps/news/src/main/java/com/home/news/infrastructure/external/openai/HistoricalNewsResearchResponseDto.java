package com.home.news.infrastructure.external.openai;

import java.util.List;

public record HistoricalNewsResearchResponseDto(
	List<HistoricalNewsCandidateDto> candidates
) {
}
