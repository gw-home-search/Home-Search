package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.NewsRuntimeProperties;
import com.home.news.infrastructure.persistence.JdbcNewsPostgresTestSupport;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

class HistoricalNewsSeedImporterTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private JdbcNewsRepository repository;
	private NewsRuntimeProperties properties;

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void setUp() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
		repository = new JdbcNewsRepository(jdbcClient);
		properties = properties();
	}

	@Test
	@DisplayName("MANUAL_APPROVED Obsidian note만 AI seed observation과 feature로 import한다")
	void importsOnlyManualApprovedNotes(@TempDir Path tempDir) throws Exception {
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06-02-approved.md"), "MANUAL_APPROVED", "");
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06-03-needs-review.md"), "NEEDS_REVIEW", "operator");
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06-04-rejected.md"), "REJECTED", "operator");

		HistoricalNewsSeedImporter importer = importer();

		HistoricalNewsSeedImportResult result = importer.importApprovedNotes(tempDir);

		assertThat(result.scannedCount()).isEqualTo(3);
		assertThat(result.importedCount()).isEqualTo(1);
		assertThat(result.skippedCount()).isEqualTo(2);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isEqualTo(1);

		String observationMetadata = jdbcClient.sql("""
			SELECT
			    source || '|' ||
			    discovery_method || '|' ||
			    availability_basis || '|' ||
			    verification_status || '|' ||
			    model_dataset_tier || '|' ||
			    COALESCE(raw_provider_payload ->> 'reviewed_by', '') || '|' ||
			    COALESCE(raw_provider_payload ->> 'published_date_precision', '') || '|' ||
			    published_at::text
			FROM news.article_observation
			""").query(String.class).single();

		assertThat(observationMetadata)
			.contains("AI_ASSISTED_WEB_RESEARCH|OPENAI_WEB_SEARCH|AI_ASSISTED_RESEARCH_SEED|MANUAL_APPROVED|EXPERIMENTAL_SEED")
			.contains("fallback-reviewer")
			.contains("DATE")
			.contains("2020-06-02 00:00:00+09");
	}

	@Test
	@DisplayName("같은 URL/date/publisher/title approved note는 observation과 feature를 중복 생성하지 않는다")
	void duplicateApprovedNotesAreIdempotent(@TempDir Path tempDir) throws Exception {
		writeNote(tempDir.resolve("first.md"), "MANUAL_APPROVED", "reviewer-1");
		writeNote(tempDir.resolve("second.md"), "MANUAL_APPROVED", "reviewer-2");

		HistoricalNewsSeedImporter importer = importer();

		HistoricalNewsSeedImportResult result = importer.importApprovedNotes(tempDir);

		assertThat(result.scannedCount()).isEqualTo(2);
		assertThat(result.importedCount()).isEqualTo(1);
		assertThat(result.duplicateCount()).isEqualTo(1);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isEqualTo(1);
	}

	private HistoricalNewsSeedImporter importer() {
		return new HistoricalNewsSeedImporter(
			repository,
			properties,
			Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC),
			objectMapper
		);
	}

	private NewsRuntimeProperties properties() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.getResearchSeed().setModel("test-model");
		properties.getResearchSeed().setPromptVersion("ai-research-seed-prompt-v1");
		properties.getResearchSeed().setSchemaVersion("ai-research-seed-schema-v1");
		properties.getResearchSeed().setDefaultReviewer("fallback-reviewer");
		return properties;
	}

	private void writeNote(Path path, String verificationStatus, String reviewedBy) throws Exception {
		Files.createDirectories(path.getParent());
		Files.writeString(path, """
			---
			verification_status: %s
			source: AI_ASSISTED_WEB_RESEARCH
			discovery_method: OPENAI_WEB_SEARCH
			availability_basis: AI_ASSISTED_RESEARCH_SEED
			model_dataset_tier: EXPERIMENTAL_SEED
			title: 강남 재건축 규제 완화
			publisher: Example Daily
			published_date: 2020-06-02
			url: https://example.com/article?utm_source=test
			url_citation: https://example.com/article
			region_bucket: SEOUL_GANGNAM_GU
			topic: policy_regulation
			impact_target: sale_price
			impact_direction_hint: up
			model_utility: high
			confidence: 0.870
			reviewed_by: %s
			---
			# 강남 재건축 규제 완화

			- Source link: https://example.com/article
			- Review status: %s
			""".formatted(verificationStatus, reviewedBy, verificationStatus));
	}
}
