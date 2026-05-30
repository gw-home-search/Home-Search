package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.infrastructure.persistence.map.JdbcMapMarkerRepository;
import com.home.infrastructure.persistence.read.JdbcPropertyReadRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.read.dto.TradeResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtmsStorageQualityJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("같은 PNU와 지번의 다른 aptSeq는 같은 parcel 아래 별도 complex와 trade로 저장되고 marker는 parcel 기준으로 묶인다")
	void samePnuDifferentAptSeqStoresSeparateComplexTradesAndGroupedParcelMarker() {
		OpenApiTradeIngestService service = ingestService(coordinates(Map.of(
			"1168010300101850000", new ParcelCoordinate(
				new BigDecimal("37.4890754"),
				new BigDecimal("127.0715119")
			)
		)));

		IngestResult result = service.ingest(batch(List.of(
			rtmsItem("11680-290", "개포주공6단지", 31, "318,000", 6, "53.06", "605", "185"),
			rtmsItem("11680-578", "개포주공7단지", 13, "342,000", 12, "60.76", "707", "185")
		)));

		assertThat(result).isEqualTo(new IngestResult(2, 2, 2, 0, 0, 0, 0));
		assertThat(rawStatusCounts())
			.containsEntry("NORMALIZED", 2L)
			.hasSize(1);
		assertThat(matchedEvidenceCount()).isEqualTo(2);
		assertThat(normalizedRawWithoutMatchedEvidence()).isZero();
		assertThat(activeTradeWithoutComplexParcel()).isZero();
		assertThat(parcelCount("1168010300101850000")).isEqualTo(1);
		assertThat(complexesForPnu("1168010300101850000"))
			.containsExactlyInAnyOrder(
				tuple("11680-290", "개포주공6단지", 1L),
				tuple("11680-578", "개포주공7단지", 1L)
			);
		assertThat(activeTradeAuditForPnu("1168010300101850000"))
			.containsExactlyInAnyOrder(
				tuple("11680-290", "RTMS:11680-290", "RTMS", "RTMS:", true),
				tuple("11680-578", "RTMS:11680-578", "RTMS", "RTMS:", true)
			);

		List<ComplexMarkerResponse> markers = new JdbcMapMarkerRepository(jdbcClient).findComplexMarkers(bounds());
		assertThat(markers)
			.singleElement()
			.satisfies(marker -> {
				assertThat(marker.parcelId()).isEqualTo(parcelId("1168010300101850000"));
				assertThat(marker.latestDealAmount()).isEqualTo(318000L);
			});
		assertThat(new JdbcPropertyReadRepository(jdbcClient).findTradeList(parcelId("1168010300101850000")))
			.hasValueSatisfying(response -> assertThat(response.trades())
				.extracting(TradeResponse::dealAmount, TradeResponse::aptDong)
				.containsExactly(tuple(318000L, "605"), tuple(342000L, "707")));
	}

	@Test
	@DisplayName("RTMS cancellation은 CANCELED raw로 남고 기존 trade를 soft-delete해 public read path에서 제외한다")
	void cancellationStaysAsRawEvidenceAndDeletesPublicTrade() {
		seedComplex();
		OpenApiTradeIngestService service = ingestService((pnu, item) -> Optional.empty());

		IngestResult inserted = service.ingest(batch(List.of(rtmsItem(
			"APT-501",
			"Sample Apartment",
			1,
			"125,000",
			12,
			"84.93",
			"101",
			"140-1"
		))));
		IngestResult canceled = service.ingest(batch(List.of(canceledRtmsItem(
			"APT-501",
			"Sample Apartment",
			1,
			"125,000",
			12,
			"84.93",
			"101",
			"140-1"
		))));

		assertThat(inserted).isEqualTo(new IngestResult(1, 1, 1, 0, 0, 0, 0));
		assertThat(canceled).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0, 0));
		assertThat(rawStatusCounts())
			.containsEntry("CANCELED", 1L)
			.containsEntry("NORMALIZED", 1L)
			.hasSize(2);
		assertThat(activeTradeCount()).isZero();
		assertThat(deletedTradeCount()).isEqualTo(1);
		assertThat(normalizedRawWithoutMatchedEvidence()).isZero();
		assertThat(activeTradeWithoutComplexParcel()).isZero();
		assertThat(new JdbcPropertyReadRepository(jdbcClient).findTradeList(1001L))
			.hasValueSatisfying(response -> assertThat(response.trades()).isEmpty());
		assertThat(new JdbcMapMarkerRepository(jdbcClient).findComplexMarkers(bounds()))
			.singleElement()
			.extracting(ComplexMarkerResponse::latestDealAmount)
			.isNull();
	}

	@Test
	@DisplayName("raw outcome count는 NORMALIZED, CANCELED, DUPLICATE, MATCH_FAILED, PARSE_FAILED status 합계로 설명된다")
	void rawOutcomeCountsExplainEveryStoredRawRow() {
		seedComplex();
		OpenApiTradeIngestService service = ingestService((pnu, item) -> Optional.empty());

		IngestResult result = service.ingest(batch(List.of(
			rtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "84.93", "101", "140-1"),
			canceledRtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "84.93", "101", "140-1"),
			rtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "84.93", "101", "140-1"),
			rtmsItem("APT-501", "Sample Apartment", 2, null, 12, "84.93", "102", "140-1"),
			rtmsItem("APT-404", "Missing Apartment", 3, "130,000", 7, "59.98", "301", "999-1")
		)));

		Map<String, Long> statusCounts = rawStatusCounts();
		assertThat(result).isEqualTo(new IngestResult(5, 5, 1, 1, 1, 1, 1));
		assertThat(rawCount()).isEqualTo(5);
		assertThat(rawOutcomeCount(statusCounts)).isEqualTo(rawCount());
		assertThat(statusCounts)
			.containsEntry("NORMALIZED", 1L)
			.containsEntry("CANCELED", 1L)
			.containsEntry("DUPLICATE", 1L)
			.containsEntry("MATCH_FAILED", 1L)
			.containsEntry("PARSE_FAILED", 1L)
			.hasSize(5);
		assertThat(activeTradeCount()).isZero();
		assertThat(deletedTradeCount()).isEqualTo(1);
		assertThat(normalizedRawWithoutMatchedEvidence()).isZero();
		assertThat(activeTradeWithoutComplexParcel()).isZero();
	}

	private OpenApiTradeIngestService ingestService(ParcelCoordinateResolver coordinateResolver) {
		return new OpenApiTradeIngestService(
			new JdbcRawTradeIngestRepository(jdbcClient),
			new JdbcNormalizedTradeRepository(jdbcClient, transactionTemplate),
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, coordinateResolver),
			new JdbcTradeMatchEvidenceRepository(jdbcClient)
		);
	}

	private ParcelCoordinateResolver coordinates(Map<String, ParcelCoordinate> coordinates) {
		return (pnu, item) -> Optional.ofNullable(coordinates.get(pnu));
	}

	private OpenApiTradeIngestBatch batch(List<OpenApiTradeItem> items) {
		return new OpenApiTradeIngestBatch("RTMS", "11680", "202512", 1, items);
	}

	private OpenApiTradeItem rtmsItem(
		String aptSeq,
		String aptName,
		int dealDay,
		String dealAmount,
		int floor,
		String exclArea,
		String aptDong,
		String jibun
	) {
		return new OpenApiTradeItem(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			12,
			2025,
			Double.parseDouble(exclArea),
			floor,
			jibun,
			"11680",
			"10300",
			"""
				{"aptSeq":"%s","aptNm":"%s","dealDay":%d,"dealAmount":"%s","floor":%d,"exclArea":"%s","aptDong":"%s","jibun":"%s"}
				""".formatted(aptSeq, aptName, dealDay, dealAmount, floor, exclArea, aptDong, jibun)
		);
	}

	private OpenApiTradeItem canceledRtmsItem(
		String aptSeq,
		String aptName,
		int dealDay,
		String dealAmount,
		int floor,
		String exclArea,
		String aptDong,
		String jibun
	) {
		return new OpenApiTradeItem(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			12,
			2025,
			Double.parseDouble(exclArea),
			floor,
			jibun,
			"11680",
			"10300",
			"""
				{"aptSeq":"%s","aptNm":"%s","dealDay":%d,"dealAmount":"%s","floor":%d,"exclArea":"%s","aptDong":"%s","jibun":"%s","cdealType":"O","cdealDay":"26.03.12"}
				""".formatted(aptSeq, aptName, dealDay, dealAmount, floor, exclArea, aptDong, jibun),
			"O",
			"26.03.12",
			null
		);
	}

	private ComplexMarkersRequest bounds() {
		return new ComplexMarkersRequest(
			37.45,
			127.00,
			37.55,
			127.12,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private Map<String, Long> rawStatusCounts() {
		return jdbcClient.sql("""
			SELECT status, count(*) AS count
			FROM raw_trade_ingest
			GROUP BY status
			ORDER BY status
			""")
			.query((resultSet, rowNumber) -> Map.entry(
				resultSet.getString("status"),
				resultSet.getLong("count")
			))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(
				Map.Entry::getKey,
				Map.Entry::getValue,
				Long::sum,
				java.util.LinkedHashMap::new
			));
	}

	private long rawOutcomeCount(Map<String, Long> statusCounts) {
		return statusCounts.values().stream()
			.mapToLong(Long::longValue)
			.sum();
	}

	private long matchedEvidenceCount() {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM trade_match_evidence
			WHERE match_status = 'MATCHED'
			""")
			.query(Long.class)
			.single();
	}

	private long normalizedRawWithoutMatchedEvidence() {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM raw_trade_ingest r
			LEFT JOIN trade_match_evidence e ON e.raw_ingest_id = r.id
			WHERE r.status = 'NORMALIZED'
			  AND e.match_status IS DISTINCT FROM 'MATCHED'
			""")
			.query(Long.class)
			.single();
	}

	private long activeTradeWithoutComplexParcel() {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM trade t
			LEFT JOIN complex c ON c.id = t.complex_id
			LEFT JOIN parcel p ON p.id = c.parcel_id
			WHERE t.deleted_at IS NULL
			  AND (c.id IS NULL OR p.id IS NULL)
			""")
			.query(Long.class)
			.single();
	}

	private long parcelCount(String pnu) {
		return jdbcClient.sql("SELECT count(*) FROM parcel WHERE pnu = :pnu")
			.param("pnu", pnu)
			.query(Long.class)
			.single();
	}

	private Long parcelId(String pnu) {
		return jdbcClient.sql("SELECT id FROM parcel WHERE pnu = :pnu")
			.param("pnu", pnu)
			.query(Long.class)
			.single();
	}

	private List<org.assertj.core.groups.Tuple> complexesForPnu(String pnu) {
		return jdbcClient.sql("""
			SELECT c.apt_seq, c.name, count(t.id) AS trade_count
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN trade t ON t.complex_id = c.id AND t.deleted_at IS NULL
			WHERE p.pnu = :pnu
			GROUP BY c.apt_seq, c.name
			ORDER BY c.apt_seq
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> tuple(
				resultSet.getString("apt_seq"),
				resultSet.getString("name"),
				resultSet.getLong("trade_count")
			))
			.list();
	}

	private List<org.assertj.core.groups.Tuple> activeTradeAuditForPnu(String pnu) {
		return jdbcClient.sql("""
			SELECT
			    t.apt_seq,
			    t.complex_pk,
			    t.source,
			    left(t.source_key, 5) AS source_key_prefix,
			    t.raw_ingest_id IS NOT NULL AS raw_linked
			FROM trade t
			JOIN complex c ON c.id = t.complex_id
			JOIN parcel p ON p.id = c.parcel_id
			WHERE p.pnu = :pnu
			  AND t.deleted_at IS NULL
			ORDER BY t.apt_seq
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> tuple(
				resultSet.getString("apt_seq"),
				resultSet.getString("complex_pk"),
				resultSet.getString("source"),
				resultSet.getString("source_key_prefix"),
				resultSet.getBoolean("raw_linked")
			))
			.list();
	}

	private long activeTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NULL")
			.query(Long.class)
			.single();
	}

	private long deletedTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NOT NULL")
			.query(Long.class)
			.single();
	}
}
