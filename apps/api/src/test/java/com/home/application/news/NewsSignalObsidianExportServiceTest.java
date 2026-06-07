package com.home.application.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsSignalObsidianExportServiceTest {

	@Test
	@DisplayName("news signal obsidian export service는 first_seen_at 일자 범위로 daily note를 저장한다")
	void exportsDailyNoteByFirstSeenDateRange() {
		RecordingNewsSignalObsidianExportRepository repository = new RecordingNewsSignalObsidianExportRepository(
			List.of(row(1L, 10L, "naver-news:1"), row(2L, 20L, "naver-news:2"))
		);
		RecordingNewsSignalObsidianExportWriter writer = new RecordingNewsSignalObsidianExportWriter();
		NewsSignalObsidianExportService service = new NewsSignalObsidianExportService(
			repository,
			new NewsSignalObsidianMarkdownRenderer(),
			writer
		);
		NewsSignalObsidianExportCommand command = new NewsSignalObsidianExportCommand(
			Path.of("/tmp/obsidian"),
			LocalDate.parse("2026-06-07"),
			ZoneId.of("Asia/Seoul"),
			1
		);

		NewsSignalObsidianExportResult result = service.exportDaily(command);

		assertThat(repository.startInclusive)
			.isEqualTo(OffsetDateTime.parse("2026-06-07T00:00:00+09:00"));
		assertThat(repository.endExclusive)
			.isEqualTo(OffsetDateTime.parse("2026-06-08T00:00:00+09:00"));
		assertThat(repository.limit).isEqualTo(2);
		assertThat(writer.outputRoot).isEqualTo(Path.of("/tmp/obsidian"));
		assertThat(writer.date).isEqualTo(LocalDate.parse("2026-06-07"));
		assertThat(writer.markdown).contains("feature_count: 1", "article_count: 1", "truncated: true");
		assertThat(writer.markdown).contains("naver-news:1");
		assertThat(writer.markdown).doesNotContain("naver-news:2");
		assertThat(result.featureCount()).isEqualTo(1);
		assertThat(result.articleCount()).isEqualTo(1);
		assertThat(result.truncated()).isTrue();
		assertThat(result.path()).isEqualTo(Path.of("/tmp/obsidian/news-signals/daily/2026-06-07.md"));
	}

	@Test
	@DisplayName("news signal obsidian export service는 빈 날짜도 daily note로 저장한다")
	void exportsEmptyDailyNote() {
		RecordingNewsSignalObsidianExportRepository repository = new RecordingNewsSignalObsidianExportRepository(
			List.of()
		);
		RecordingNewsSignalObsidianExportWriter writer = new RecordingNewsSignalObsidianExportWriter();
		NewsSignalObsidianExportService service = new NewsSignalObsidianExportService(
			repository,
			new NewsSignalObsidianMarkdownRenderer(),
			writer
		);

		NewsSignalObsidianExportResult result = service.exportDaily(new NewsSignalObsidianExportCommand(
			Path.of("/tmp/obsidian"),
			LocalDate.parse("2026-06-07"),
			ZoneId.of("Asia/Seoul"),
			100
		));

		assertThat(writer.markdown).contains("feature_count: 0", "article_count: 0", "## Signals");
		assertThat(result.featureCount()).isZero();
		assertThat(result.articleCount()).isZero();
		assertThat(result.truncated()).isFalse();
	}

	private static NewsSignalDatasetRow row(long featureId, long articleObservationId, String sourceKey) {
		return new NewsSignalDatasetRow(
			featureId,
			articleObservationId,
			"NAVER_NEWS",
			sourceKey,
			"Sample Publisher",
			"강남 재건축 규제 완화 기대감",
			"https://example.com/news/" + featureId,
			"https://developers.naver.com/docs/serviceapi/search/news/news.md",
			"공식 API snippet 본문",
			OffsetDateTime.parse("2026-06-07T08:50:00+09:00"),
			OffsetDateTime.parse("2026-06-07T08:50:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:15:00+09:00"),
			LocalDate.parse("2026-06-07"),
			LocalDate.parse("2026-06-07"),
			OffsetDateTime.parse("2026-06-07T09:15:00+09:00"),
			List.of("seoul", "gangnam-gu"),
			List.of(Map.of()),
			List.of("policy", "reconstruction"),
			"sale_price",
			"up",
			"positive",
			0.82,
			"title-snippet-signal-20260607-r1",
			"title",
			OffsetDateTime.parse("2026-06-07T09:16:00+09:00")
		);
	}

	private static class RecordingNewsSignalObsidianExportRepository implements NewsSignalObsidianExportRepository {

		private final List<NewsSignalDatasetRow> rows;
		private OffsetDateTime startInclusive;
		private OffsetDateTime endExclusive;
		private int limit;

		RecordingNewsSignalObsidianExportRepository(List<NewsSignalDatasetRow> rows) {
			this.rows = rows;
		}

		@Override
		public List<NewsSignalDatasetRow> findObservedBetween(
			OffsetDateTime startInclusive,
			OffsetDateTime endExclusive,
			int limit
		) {
			this.startInclusive = startInclusive;
			this.endExclusive = endExclusive;
			this.limit = limit;
			return rows.stream().limit(limit).toList();
		}
	}

	private static class RecordingNewsSignalObsidianExportWriter implements NewsSignalObsidianExportWriter {

		private Path outputRoot;
		private LocalDate date;
		private String markdown;

		@Override
		public Path writeDailyNote(Path outputRoot, LocalDate date, String markdown) {
			this.outputRoot = outputRoot;
			this.date = date;
			this.markdown = markdown;
			return outputRoot.resolve("news-signals/daily/" + date + ".md");
		}
	}
}
