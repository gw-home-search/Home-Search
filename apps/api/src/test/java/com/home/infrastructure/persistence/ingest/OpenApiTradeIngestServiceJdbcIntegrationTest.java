package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RawTradeIngestStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiTradeIngestServiceJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("raw rows are saved before normalized insert and duplicate raw rows remain queryable")
	void rawRowsPrecedeNormalizedInsertAndDuplicateRawRowsRemainQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"), rtmsItem(" 125000 "))
		));

		assertThat(result.rawSaved()).isEqualTo(2);
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.duplicateSkipped()).isEqualTo(1);
		assertThat(rawCount()).isEqualTo(2);
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
		assertThat(firstRawCreatedAt()).isBeforeOrEqualTo(firstTradeCreatedAt());
	}

	@Test
	@DisplayName("local PostGIS matcher keeps failed RTMS matches queryable")
	void localPostgisMatcherKeepsFailedRtmsMatchesQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(new OpenApiTradeItem(
				"101",
				"Missing Apartment",
				"APT-404",
				"125,000",
				1,
				12,
				2025,
				84.93,
				12,
				"999-1",
				"11680",
				"10300",
				"{\"aptSeq\":\"APT-404\",\"jibun\":\"999-1\"}"
			))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED))
			.singleElement()
			.satisfies(record -> {
				assertThat(record.source()).isEqualTo("RTMS");
				assertThat(record.failureReason()).contains("APT-404");
				assertThat(record.failureReason()).contains("1168010300109990001");
			});
	}

	@Test
	@DisplayName("live RTMS rows bootstrap parcel and complex master before normalized insert when coordinates resolve")
	void bootstrapsParcelAndComplexMasterBeforeNormalizedInsertWhenCoordinatesResolve() {
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(
				jdbcClient,
				coordinates("1168010300107770001", "37.5012345", "127.0543210")
			)
		);
		OpenApiTradeItem liveItem = liveRtmsItem("APT-LIVE-501", "Live Sample Apartment", "777-1");

		IngestResult first = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveItem)
		));
		IngestResult duplicate = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveItem)
		));

		assertThat(first.rawSaved()).isEqualTo(1);
		assertThat(first.normalizedInserted()).isEqualTo(1);
		assertThat(first.matchFailed()).isZero();
		assertThat(duplicate.rawSaved()).isEqualTo(1);
		assertThat(duplicate.normalizedInserted()).isZero();
		assertThat(duplicate.duplicateSkipped()).isEqualTo(1);
		assertThat(parcelCount("1168010300107770001")).isEqualTo(1);
		assertThat(complexCountByAptSeq("APT-LIVE-501")).isEqualTo(1);
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
		assertThat(normalizedTradeAudit("APT-LIVE-501"))
			.containsEntry("apt_seq", "APT-LIVE-501")
			.containsEntry("complex_pk", "RTMS:APT-LIVE-501");
	}

	@Test
	@DisplayName("live RTMS rows without resolved coordinates remain explainable match failures without fake parcel")
	void coordinateMissingLeavesExplainableMatchFailureWithoutFakeParcel() {
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty())
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-LIVE-404", "Missing Live Apartment", "888-1"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(parcelCount("1168010300108880001")).isZero();
		assertThat(complexCountByAptSeq("APT-LIVE-404")).isZero();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED))
			.singleElement()
			.satisfies(record -> {
				assertThat(record.failureReason()).contains("1168010300108880001");
				assertThat(record.failureReason()).contains("coordinate unavailable");
			});
	}

	private OpenApiTradeItem rtmsItem(String dealAmount) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			dealAmount,
			1,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-501\",\"dealAmount\":\"%s\"}".formatted(dealAmount)
		);
	}

	private OpenApiTradeItem liveRtmsItem(String aptSeq, String aptName, String jibun) {
		return new OpenApiTradeItem(
			"101",
			aptName,
			aptSeq,
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"jibun\":\"%s\"}".formatted(aptSeq, aptName, jibun)
		);
	}

	private ParcelCoordinateResolver coordinates(String expectedPnu, String latitude, String longitude) {
		ParcelCoordinate coordinate = new ParcelCoordinate(new BigDecimal(latitude), new BigDecimal(longitude));
		return (pnu, item) -> expectedPnu.equals(pnu) ? Optional.of(coordinate) : Optional.empty();
	}

	private long parcelCount(String pnu) {
		return jdbcClient.sql("SELECT count(*) FROM parcel WHERE pnu = :pnu")
			.param("pnu", pnu)
			.query(Long.class)
			.single();
	}

	private long complexCountByAptSeq(String aptSeq) {
		return jdbcClient.sql("SELECT count(*) FROM complex WHERE apt_seq = :aptSeq")
			.param("aptSeq", aptSeq)
			.query(Long.class)
			.single();
	}

	private java.util.Map<String, Object> normalizedTradeAudit(String aptSeq) {
		return jdbcClient.sql("""
			SELECT complex_pk, apt_seq
			FROM trade
			WHERE apt_seq = :aptSeq
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> java.util.Map.<String, Object>of(
				"complex_pk", resultSet.getString("complex_pk"),
				"apt_seq", resultSet.getString("apt_seq")
			))
			.single();
	}

	private OffsetDateTime firstRawCreatedAt() {
		return jdbcClient.sql("SELECT created_at FROM raw_trade_ingest ORDER BY id LIMIT 1")
			.query(OffsetDateTime.class)
			.single();
	}

	private OffsetDateTime firstTradeCreatedAt() {
		return jdbcClient.sql("SELECT created_at FROM trade ORDER BY id LIMIT 1")
			.query(OffsetDateTime.class)
			.single();
	}
}
