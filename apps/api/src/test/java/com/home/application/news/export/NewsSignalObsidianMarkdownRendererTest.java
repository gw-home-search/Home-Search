package com.home.application.news.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.news.signal.NewsSignalDatasetRow;
import com.home.application.news.signal.NewsSignalFeatureExtractionPolicy;

class NewsSignalObsidianMarkdownRendererTest {

	@Test
	@DisplayName("news signal obsidian renderer는 daily front matter와 구조화 feature만 렌더링한다")
	void rendersDailyMarkdownWithoutSnippetOrReplacementSummary() {
		NewsSignalObsidianMarkdownRenderer renderer = new NewsSignalObsidianMarkdownRenderer();
		NewsSignalObsidianExportCommand command = new NewsSignalObsidianExportCommand(
			Path.of("/tmp/obsidian"),
			LocalDate.parse("2026-06-07"),
			ZoneId.of("Asia/Seoul"),
			100
		);

		String markdown = renderer.renderDailyNote(command, List.of(row()), false);

		assertThat(markdown).contains(
			"date: 2026-06-07",
			"timezone: Asia/Seoul",
			"first_seen_from_inclusive: 2026-06-07T00:00:00+09:00",
			"first_seen_before_exclusive: 2026-06-08T00:00:00+09:00",
			"generated_from: news_signal_dataset_view",
			"feature_count: 1",
			"article_count: 1",
			"publishers: [Sample Publisher]",
			"regions: [gangnam-gu, seoul]",
			"topics: [policy, reconstruction]",
			"title_keywords: [강남, 재건축, 집값]",
			"truncated: false",
			"# News Signals - 2026-06-07",
			"- [강남 재건축 규제 완화 기대감](<https://example.com/news/1>)",
			"  - title_keywords: 강남, 재건축, 집값",
			"  - impact: sale_price / up",
			"  - sentiment: positive",
			"  - confidence: 0.82",
			"  - evidence_level: title"
		);
		assertThat(markdown)
			.doesNotContain("공식 API snippet 본문")
			.doesNotContain("raw_provider_payload")
			.doesNotContain("replacement summary")
			.doesNotContain("full_text")
			.doesNotContain("html");
	}

	private static NewsSignalDatasetRow row() {
		return new NewsSignalDatasetRow(
			1L,
			10L,
			"NAVER_NEWS",
			"naver-news:1",
			"Sample Publisher",
			"강남 재건축 규제 완화 기대감",
			"https://example.com/news/1",
			"https://developers.naver.com/docs/serviceapi/search/news/news.md",
			"공식 API snippet 본문",
			OffsetDateTime.parse("2026-06-07T08:50:00+09:00"),
			OffsetDateTime.parse("2026-06-07T08:50:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:15:00+09:00"),
			LocalDate.parse("2026-06-07"),
			LocalDate.parse("2026-06-07"),
			OffsetDateTime.parse("2026-06-07T09:15:00+09:00"),
			List.of("강남", "재건축", "집값"),
			List.of("seoul", "gangnam-gu"),
			List.of(Map.of("name", "Sample Complex")),
			List.of("policy", "reconstruction"),
			"sale_price",
			"up",
			"positive",
			0.82,
			NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION,
			"title",
			OffsetDateTime.parse("2026-06-07T09:16:00+09:00")
		);
	}
}
