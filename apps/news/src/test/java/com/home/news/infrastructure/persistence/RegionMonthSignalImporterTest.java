package com.home.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalEvidenceScope;
import com.home.domain.news.RegionMonthSignalSourceKind;
import com.home.news.application.NewsSignalValidationException;
import com.home.news.application.RegionMonthSignalEvidence;
import com.home.news.application.RegionMonthSignalImporter;
import com.home.news.application.RegionMonthSignalJsonl;
import com.home.news.application.RegionMonthSignalSnapshot;
import com.home.news.application.RegionMonthSignalValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

class RegionMonthSignalImporterTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();
	private final ObjectMapper objectMapper = new ObjectMapper();

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void migrate() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
	}

	@Test
	@DisplayName("web JSONL importer는 idempotent하게 snapshot/evidence를 upsert한다")
	void importsWebJsonlIdempotently(@TempDir Path tempDir) {
		RegionMonthSignalJsonl jsonl = new RegionMonthSignalJsonl(objectMapper, new RegionMonthSignalValidator());
		Path path = tempDir.resolve("region-month-signal-web-research.jsonl");
		jsonl.write(path, List.of(sampleSnapshot()));
		RegionMonthSignalImporter importer = new RegionMonthSignalImporter(
			jsonl,
			new JdbcRegionMonthSignalRepository(jdbcClient, objectMapper)
		);

		importer.importJsonl(path);
		importer.importJsonl(path);

		assertThat(count("news.region_month_signal_snapshot")).isEqualTo(1);
		assertThat(count("news.region_month_signal_evidence")).isEqualTo(1);
	}

	@Test
	@DisplayName("web JSONL validator는 forbidden body-like key를 거부한다")
	void rejectsForbiddenJsonlKey(@TempDir Path tempDir) throws Exception {
		RegionMonthSignalJsonl jsonl = new RegionMonthSignalJsonl(objectMapper, new RegionMonthSignalValidator());
		Path path = tempDir.resolve("bad.jsonl");
		java.nio.file.Files.writeString(path, """
			{"region_bucket":"NATIONAL","signal_month":"2022-01","source_kind":"AGENT_WEB_RESEARCH","method_version":"test","dataset_tier":"EXPERIMENTAL_SEED","news_count":0,"matched_news_count":0,"direct_evidence_count":0,"inherited_evidence_count":0,"policy_positive_score":0,"policy_negative_score":0,"redevelopment_score":0,"transport_score":0,"supply_risk_score":0,"sale_market_score":0,"rental_market_score":0,"price_up_signal":0,"price_down_signal":0,"confidence":0.3,"aggregate_note":"metadata signal 없음","evidence":[],"body":"forbidden"}
			""");

		assertThatThrownBy(() -> jsonl.read(path))
			.isInstanceOf(NewsSignalValidationException.class)
			.hasMessageContaining("invalid region-month signal row");
	}

	@Test
	@DisplayName("web JSONL validator는 detail bucket의 inherited evidence를 낮은 confidence로 허용한다")
	void acceptsInheritedEvidenceForDetailBucketWithBoundedConfidence() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.SEOUL_YONGSAN_GU,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			0,
			1,
			0,
			0,
			0,
			0,
			0,
			20,
			0,
			20,
			0,
			new BigDecimal("0.500"),
			"용산구 직접 metadata 없이 상위 통계만 있는 보류 대상",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-05:SEOUL_YONGSAN_GU:1",
				"주택매매가격 동향",
				"e-나라지표",
				null,
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				List.of("market"),
				RegionMonthSignalEvidenceScope.INHERITED
			))
		);

		validator.validate(snapshot);
	}

	@Test
	@DisplayName("web JSONL validator는 detail bucket title alias가 없는 direct evidence를 낮은 confidence로 허용한다")
	void acceptsDirectEvidenceWithoutDetailAliasWithBoundedConfidence() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.SEOUL_YONGSAN_GU,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			1,
			0,
			0,
			0,
			0,
			0,
			0,
			20,
			0,
			20,
			0,
			new BigDecimal("0.500"),
			"용산구 직접 alias 없는 direct metadata evidence 보류 대상",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-05:SEOUL_YONGSAN_GU:1",
				"주택매매가격 동향",
				"e-나라지표",
				null,
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				List.of("market"),
				RegionMonthSignalEvidenceScope.DIRECT
			))
		);

		validator.validate(snapshot);
	}

	@Test
	@DisplayName("web JSONL validator는 evidence 없는 placeholder row를 거부한다")
	void rejectsPlaceholderWebResearchRow() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.SEOUL_YONGSAN_GU,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			new BigDecimal("0.300"),
			"용산구 직접 metadata evidence가 없어 세부 지역 신호를 보류한 row",
			List.of()
		);

		assertThatThrownBy(() -> validator.validate(snapshot))
			.isInstanceOf(NewsSignalValidationException.class)
			.hasMessageContaining("web research rows require evidence");
	}

	@Test
	@DisplayName("web JSONL validator는 top-level bucket alias가 없는 direct evidence를 낮은 confidence로 허용한다")
	void acceptsTopLevelEvidenceWithoutBucketAliasWithBoundedConfidence() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.GYEONGGI,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			1,
			0,
			0,
			0,
			0,
			0,
			0,
			20,
			0,
			20,
			0,
			new BigDecimal("0.500"),
			"경기 직접 alias 없는 generic 공식 지표를 잘못 연결한 row",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-05:GYEONGGI:1",
				"주택전세가격 동향",
				"e-나라지표",
				null,
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1241",
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1241",
				List.of("rental_market"),
				RegionMonthSignalEvidenceScope.DIRECT
			))
		);

		validator.validate(snapshot);
	}

	@Test
	@DisplayName("web JSONL validator는 약한 지역 매칭 근거의 높은 confidence를 거부한다")
	void rejectsHighConfidenceForWeaklyMatchedEvidence() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.SEOUL_YONGSAN_GU,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			1,
			0,
			0,
			0,
			0,
			0,
			0,
			20,
			0,
			20,
			0,
			new BigDecimal("0.700"),
			"용산구 직접 alias 없는 direct metadata evidence는 낮은 confidence만 허용",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-05:SEOUL_YONGSAN_GU:1",
				"주택매매가격 동향",
				"e-나라지표",
				null,
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				"https://www.index.go.kr/unity/potal/main/EachDtlPageDetail.do?idx_cd=1240",
				List.of("market"),
				RegionMonthSignalEvidenceScope.DIRECT
			))
		);

		assertThatThrownBy(() -> validator.validate(snapshot))
			.isInstanceOf(NewsSignalValidationException.class)
			.hasMessageContaining("weakly matched evidence");
	}

	@Test
	@DisplayName("web JSONL validator는 evidence만 있고 점수 신호가 없는 row를 거부한다")
	void rejectsWebResearchRowWithoutAnySignalScore() {
		RegionMonthSignalValidator validator = new RegionMonthSignalValidator();
		RegionMonthSignalSnapshot snapshot = new RegionMonthSignalSnapshot(
			NewsRegionBucket.NATIONAL,
			LocalDate.of(2022, 5, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			1,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			new BigDecimal("0.400"),
			"근거 metadata는 있지만 점수 신호가 없어 저장하지 않을 row",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-05:NATIONAL:1",
				"전국 아파트 시장 점검",
				"example",
				LocalDate.of(2022, 5, 1),
				"https://example.com/news",
				"https://example.com/news",
				List.of("market"),
				RegionMonthSignalEvidenceScope.DIRECT
			))
		);

		assertThatThrownBy(() -> validator.validate(snapshot))
			.isInstanceOf(NewsSignalValidationException.class)
			.hasMessageContaining("signal score");
	}

	private long count(String tableName) {
		return jdbcClient.sql("SELECT count(*) FROM " + tableName).query(Long.class).single();
	}

	private RegionMonthSignalSnapshot sampleSnapshot() {
		return new RegionMonthSignalSnapshot(
			NewsRegionBucket.NATIONAL,
			LocalDate.of(2022, 1, 1),
			RegionMonthSignalSourceKind.AGENT_WEB_RESEARCH,
			"test-method-v1",
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			1,
			1,
			1,
			0,
			20,
			0,
			0,
			0,
			0,
			20,
			0,
			20,
			0,
			new BigDecimal("0.650"),
			"전국 2022-01 metadata 1건 기반 aggregate signal",
			List.of(new RegionMonthSignalEvidence(
				"AGENT_WEB_RESEARCH:2022-01:NATIONAL:1",
				"전국 아파트 시장 정책 점검",
				"example",
				LocalDate.of(2022, 1, 15),
				"https://example.com/news",
				"https://example.com/news",
				List.of("policy"),
				RegionMonthSignalEvidenceScope.DIRECT
			))
		);
	}
}
