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
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/2020-06-02-approved.md"), "MANUAL_APPROVED", "");
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/2020-06-03-needs-review.md"), "NEEDS_REVIEW", "operator");
		writeNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/2020-06-04-rejected.md"), "REJECTED", "operator");
		writeManifest(tempDir.resolve("news-research-seed/_manifest/test-run.md"));

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
			    COALESCE(raw_provider_payload ->> 'signal_month', '') || '|' ||
			    COALESCE(raw_provider_payload ->> 'score_signal_strength', '') || '|' ||
			    COALESCE(raw_provider_payload ->> 'candidate_hash', '') || '|' ||
			    published_at::text
			FROM news.article_observation
			""").query(String.class).single();

		assertThat(observationMetadata)
			.contains("AI_ASSISTED_WEB_RESEARCH|OPENAI_WEB_SEARCH|AI_ASSISTED_RESEARCH_SEED|MANUAL_APPROVED|EXPERIMENTAL_SEED")
			.contains("fallback-reviewer")
			.contains("DATE")
			.contains("2020-06")
			.contains("STRONG")
			.contains("candidate-hash")
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

	@Test
	@DisplayName("MANUAL_APPROVED BigKinds CSV v2 note는 provider metadata만 raw payload에 보존하고 signal_month 첫날 feature로 import한다")
	void importsManualApprovedBigKindsCsvNotes(@TempDir Path tempDir) throws Exception {
		writeBigKindsNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/2020-06-02-bigkinds.md"), "MANUAL_APPROVED");
		writeBigKindsNote(tempDir.resolve("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/2020-06-03-needs-review.md"), "NEEDS_REVIEW");

		HistoricalNewsSeedImportResult result = importer().importApprovedNotes(tempDir);

		assertThat(result.scannedCount()).isEqualTo(2);
		assertThat(result.importedCount()).isEqualTo(1);
		assertThat(result.skippedCount()).isEqualTo(1);
		String imported = jdbcClient.sql("""
			SELECT
			    observation.source || '|' ||
			    observation.source_key || '|' ||
			    observation.discovery_method || '|' ||
			    observation.availability_basis || '|' ||
			    observation.verification_status || '|' ||
			    observation.provider_url || '|' ||
			    observation.news_date_kst::text || '|' ||
			    feature.feature_date_kst::text || '|' ||
			    COALESCE(observation.raw_provider_payload ->> 'source_file', '') || '|' ||
			    COALESCE(observation.raw_provider_payload ->> 'source_row_number', '') || '|' ||
			    COALESCE(observation.raw_provider_payload ->> 'keywords', '') || '|' ||
			    COALESCE(observation.raw_provider_payload ->> 'extracted_terms', '') || '|' ||
			    jsonb_exists(observation.raw_provider_payload, 'body')::text || '|' ||
			    jsonb_exists(observation.raw_provider_payload, '본문')::text || '|' ||
			    jsonb_exists(observation.raw_provider_payload, 'model')::text || '|' ||
			    jsonb_exists(observation.raw_provider_payload, 'prompt_version')::text
			FROM news.article_observation observation
			JOIN news.signal_feature feature ON feature.article_observation_id = observation.id
			""").query(String.class).single();

		assertThat(imported)
			.contains("BIGKINDS_CSV|BIGKINDS_CSV:01200201.20200602123456001|PROVIDER_EXPORT|LICENSED_HISTORICAL_EXPORT|MANUAL_APPROVED")
			.contains("http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001")
			.contains("2020-06-02|2020-06-01")
			.contains("부동산 (2020.04.01-2020.06.30).csv|2")
			.contains("재건축,강남,아파트")
			.contains("재건축,규제완화,강남")
			.endsWith("false|false|false|false");
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
			query_month: 2020-06
			query_bucket: SEOUL_GANGNAM_GU
			model: test-model
			prompt_version: research-seed-v2-gpt54
			schema_version: research-seed-schema-v2
			screening_version: research-seed-screening-v1
			score_signal_strength: STRONG
			model_utility: HIGH
			confidence: 0.870
			reason_codes: [policy]
			screening_reasons: []
			candidate_hash: candidate-hash
			reviewed_at:
			review_decision_reason:
			reviewed_by: %s
			---
			# 강남 재건축 규제 완화

			- [ ] URL 접속 가능
			- [ ] 기사 날짜가 query_month 내부
			""".formatted(verificationStatus, reviewedBy));
	}

	private void writeBigKindsNote(Path path, String verificationStatus) throws Exception {
		Files.createDirectories(path.getParent());
		Files.writeString(path, """
			---
			verification_status: %s
			source: BIGKINDS_CSV
			discovery_method: PROVIDER_EXPORT
			availability_basis: LICENSED_HISTORICAL_EXPORT
			model_dataset_tier: EXPERIMENTAL_SEED
			title: 강남 재건축 규제 완화
			publisher: 경인일보
			published_date: 2020-06-02
			url: https://example.com/article
			url_citation: http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001
			region_bucket: SEOUL_GANGNAM_GU
			topic: reconstruction_redevelopment
			impact_target: sale_price
			impact_direction_hint: unknown
			signal_month: 2020-06
			confidence: 0.800
			screening_reasons: []
			candidate_hash: csv-candidate-hash
			reviewed_at:
			reviewed_by: csv-reviewer
			review_decision_reason:
			source_file: 부동산 (2020.04.01-2020.06.30).csv
			source_row_number: 2
			provider_record_id: 01200201.20200602123456001
			original_url: https://example.com/article
			keywords: 재건축,강남,아파트
			extracted_terms: 재건축,규제완화,강남
			region_entities: 서울,강남구
			organization_entities: 국토교통부
			---
			## 검수 참고
			- 키워드: 재건축,강남,아파트
			- 특성추출: 재건축,규제완화,강남
			""".formatted(verificationStatus));
	}

	private void writeManifest(Path path) throws Exception {
		Files.createDirectories(path.getParent());
		Files.writeString(path, """
			---
			run_id: test-run
			---
			planned: 3
			accepted: 1
			""");
	}
}
