package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataLookupEvidence;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.domain.complex.metadata.ComplexMetadataLookupPath;
import com.home.infrastructure.persistence.ingest.matching.JdbcOdcMetadataGapFillRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcOdcMetadataGapFillRepositoryTest extends JdbcMigrationTestSupport {
	private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
	private JdbcOdcMetadataGapFillRepository repository;

	@BeforeEach
	void migrate() {
		flyway(null).clean();
		flyway(null).migrate();
		repository = new JdbcOdcMetadataGapFillRepository(jdbcClient, transactionTemplate);
	}

	@Test
	@DisplayName("ODC core 결측만 선택하고 이미 ODC attempt가 있는 대상은 재선택하지 않는다")
	void selectsOnlyCoreGapsAndExcludesRestartedRequest() {
		seed(501, 1001, "1168010300101400001", null, 740, null);
		seed(502, 1002, "1168010300101400002", 8, 740, LocalDate.of(2015, 1, 1));

		var targets = repository.findTargets(20, null, 1000, REQUEST_ID);

		assertThat(targets).extracting(target -> target.lookup().complexId()).containsExactly(501L);
		repository.recordAmbiguous(targets.get(0), REQUEST_ID);
		assertThat(repository.findTargets(20, null, 1000, REQUEST_ID)).isEmpty();
	}

	@Test
	@DisplayName("기존 non-null core 값 충돌 시 다른 NULL 필드도 투영하지 않는다")
	void cancelsWholeProjectionWhenAnyCoreValueConflicts() {
		seed(501, 1001, "1168010300101400001", 8, null, null);
		var target = repository.findTargets(20, null, 1000, REQUEST_ID).get(0);
		ComplexMetadataResolution resolution = ComplexMetadataResolution.resolved("ODC",
			new ComplexMetadata(9, 740, null, null, null, null, null, LocalDate.of(2015, 1, 1)))
			.withLookupEvidence(new ComplexMetadataLookupEvidence(ComplexMetadataLookupPath.CANONICAL_PNU,
				target.lookup().pnu(), target.lookup().pnu(), null, 1));

		var outcome = repository.saveResolution(target, resolution, REQUEST_ID);

		assertThat(outcome.projectionApplied()).isFalse();
		assertThat(jdbcClient.sql("SELECT unit_cnt FROM complex WHERE id=501").query(Integer.class).optional()).isEmpty();
		assertThat(jdbcClient.sql("SELECT projection_applied FROM complex_metadata_enrichment_attempt WHERE complex_id=501")
			.query(Boolean.class).single()).isFalse();
	}

	@Test
	@DisplayName("충돌 없는 ODC 후보는 NULL core만 채우고 같은 request ID 저장은 idempotent하다")
	void fillsOnlyNullCoreValuesAndMakesSameRequestIdempotent() {
		seed(501, 1001, "1168010300101400001", 8, null, null);
		var target = repository.findTargets(20, null, 1000, REQUEST_ID).get(0);
		ComplexMetadataResolution resolution = ComplexMetadataResolution.resolved("ODC",
			new ComplexMetadata(8, 740, null, null, null, null, null, LocalDate.of(2015, 1, 1)))
			.withLookupEvidence(new ComplexMetadataLookupEvidence(ComplexMetadataLookupPath.CANONICAL_PNU,
				target.lookup().pnu(), target.lookup().pnu(), null, 1));

		var first = repository.saveResolution(target, resolution, REQUEST_ID);
		var second = repository.saveResolution(target, resolution, REQUEST_ID);

		assertThat(first.projectionApplied()).isTrue();
		assertThat(second).isEqualTo(first);
		assertThat(jdbcClient.sql("SELECT dong_cnt||':'||unit_cnt||':'||use_date FROM complex WHERE id=501")
			.query(String.class).single()).isEqualTo("8:740:2015-01-01");
		assertThat(jdbcClient.sql("SELECT count(*) FROM complex_metadata_enrichment_attempt WHERE complex_id=501")
			.query(Integer.class).single()).isOne();
	}

	@Test
	@DisplayName("ODC source missing 결과는 projection 없이 retry evidence를 저장한다")
	void storesSourceMissingWithoutProjection() {
		seed(501, 1001, "1168010300101400001", null, null, null);
		var target = repository.findTargets(20, null, 1000, REQUEST_ID).get(0);
		ComplexMetadataResolution unavailable = ComplexMetadataResolution.unavailable("ODC", "source unavailable")
			.withLookupEvidence(new ComplexMetadataLookupEvidence(ComplexMetadataLookupPath.CANONICAL_PNU,
				target.lookup().pnu(), null, null, 0));

		var outcome = repository.saveResolution(target, unavailable, REQUEST_ID);

		assertThat(outcome.projectionApplied()).isFalse();
		assertThat(jdbcClient.sql("SELECT next_attempt_at IS NOT NULL FROM complex_metadata_enrichment_attempt WHERE complex_id=501")
			.query(Boolean.class).single()).isTrue();
	}

	@Test
	@DisplayName("새 request ID의 최초 gap-fill은 아직 due가 아닌 과거 attempt를 건너뛰고 미처리 단지로 진행한다")
	void newGapFillRequestSkipsPreviouslyAttemptedComplexes() {
		seed(501, 1001, "1168010300101400001", null, null, null);
		seed(502, 1002, "1168010300101400002", null, null, null);
		var first = repository.findTargets(1, null, 1000, REQUEST_ID).get(0);
		ComplexMetadataResolution unavailable = ComplexMetadataResolution.unavailable("ODC", "source unavailable")
			.withLookupEvidence(new ComplexMetadataLookupEvidence(ComplexMetadataLookupPath.CANONICAL_PNU,
				first.lookup().pnu(), null, null, 0));
		repository.saveResolution(first, unavailable, REQUEST_ID);

		UUID nextRequestId = UUID.fromString("123e4567-e89b-12d3-a456-426614174021");
		var targets = repository.findTargets(1, null, 1000, nextRequestId);

		assertThat(targets).extracting(target -> target.lookup().complexId()).containsExactly(502L);
	}

	private void seed(long id, long parcelId, String pnu, Integer dongCnt, Integer unitCnt, LocalDate useDate) {
		jdbcClient.sql("INSERT INTO parcel(id,pnu,address) VALUES (:id,:pnu,'Sample')")
			.param("id", parcelId).param("pnu", pnu).update();
		jdbcClient.sql("""
			INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,dong_cnt,unit_cnt,use_date)
			VALUES (:id,:parcel_id,:complex_pk,:apt_seq,'Sample Apartment',:dong,:unit,:use_date)
			""").param("id", id).param("parcel_id", parcelId).param("complex_pk", "RTMS:" + id)
			.param("apt_seq", "APT-" + id).param("dong", dongCnt).param("unit", unitCnt)
			.param("use_date", useDate).update();
	}
}
