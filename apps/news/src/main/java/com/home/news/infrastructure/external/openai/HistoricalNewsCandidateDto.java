package com.home.news.infrastructure.external.openai;

import java.math.BigDecimal;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalModelUtility;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalScoreSignalStrength;

public record HistoricalNewsCandidateDto(
	String title,
	String publisher,
	String published_date,
	String url,
	String url_citation,
	String query_month,
	NewsRegionBucket region_bucket,
	NewsSignalTopic topic,
	SignalImpactTarget impact_target,
	SignalImpactDirection impact_direction_hint,
	SignalScoreSignalStrength score_signal_strength,
	SignalModelUtility model_utility,
	BigDecimal confidence,
	List<String> reason_codes
) {
}
