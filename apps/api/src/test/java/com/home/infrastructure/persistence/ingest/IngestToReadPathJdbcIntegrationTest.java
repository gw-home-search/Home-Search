package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RawTradeIngestStatus;
import com.home.application.ingest.TradeMatchStatus;
import com.home.infrastructure.persistence.map.JdbcMapMarkerRepository;
import com.home.infrastructure.persistence.read.JdbcPropertyReadRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestToReadPathJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS ingest로 저장된 normalized trade는 map/detail/trade read path를 backing하고 보류 row는 public 조회에서 제외된다")
	void ingestedNormalizedTradesBackMapDetailAndTradeReadPaths() {
		seedExplorationMaster();
		OpenApiTradeIngestService ingestService = ingestService();

		IngestResult result = ingestService.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(
				rtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "140-1", "101"),
				rtmsItem("APT-501", "Sample Apartment", 15, "130,000", 15, "140-1", "101"),
				rtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "140-1", "101"),
				rtmsItem("APT-501", "Conflicting Apartment", 20, "150,000", 20, "999-1", "201")
			)
		));

		assertThat(result).isEqualTo(new IngestResult(4, 4, 2, 1, 0, 1, 0));
		assertThat(rawCount()).isEqualTo(4);
		assertThat(tradeCount()).isEqualTo(2);
		assertThat(rawStatusCounts())
			.containsExactly(
				tuple(RawTradeIngestStatus.DUPLICATE.name(), 1L),
				tuple(RawTradeIngestStatus.MATCH_FAILED.name(), 1L),
				tuple(RawTradeIngestStatus.NORMALIZED.name(), 2L)
			);
		assertThat(evidenceStatuses())
			.containsExactly(
				TradeMatchStatus.MATCHED,
				TradeMatchStatus.MATCHED,
				TradeMatchStatus.PNU_CONFLICT
			);

		JdbcMapMarkerRepository mapRepository = new JdbcMapMarkerRepository(jdbcClient);
		assertThat(mapRepository.findComplexMarkers(boundsRequest()))
			.extracting(
				ComplexMarkerResponse::parcelId,
				ComplexMarkerResponse::latestDealAmount,
				ComplexMarkerResponse::unitCntSum
			)
			.containsExactly(tuple(1001L, 130000L, 740L));

		JdbcPropertyReadRepository readRepository = new JdbcPropertyReadRepository(jdbcClient);
		assertThat(readRepository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(1001L);
				assertThat(detail.name()).isEqualTo("Sample Apartment");
				assertThat(detail.tradeName()).isEqualTo("Sample trade name");
			});
		assertThat(readRepository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("dealDate", "dealAmount", "aptDong", "floor")
				.containsExactly(
					tuple(LocalDate.of(2025, 12, 15), 130000L, "101", 15),
					tuple(LocalDate.of(2025, 12, 1), 125000L, "101", 12)
				));
		assertThat(readRepository.searchComplexes("sample"))
			.singleElement()
			.satisfies(resultRow -> {
				assertThat(resultRow.complexId()).isEqualTo(501L);
				assertThat(resultRow.parcelId()).isEqualTo(1001L);
			});
	}

	@Test
	@DisplayName("RTMS 해제 row로 soft-delete된 trade는 ingest 이후 map marker와 trade list에서 제외된다")
	void canceledIngestedTradeIsExcludedFromMapAndTradeReadPaths() {
		seedExplorationMaster();
		OpenApiTradeIngestService ingestService = ingestService();
		OpenApiTradeItem active = rtmsItem("APT-501", "Sample Apartment", 15, "130,000", 15, "140-1", "101");
		OpenApiTradeItem canceled = canceledRtmsItem("APT-501", "Sample Apartment", 15, "130,000", 15, "140-1", "101");

		IngestResult inserted = ingestService.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(
				rtmsItem("APT-501", "Sample Apartment", 1, "125,000", 12, "140-1", "101"),
				active
			)
		));
		IngestResult deleted = ingestService.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(canceled)
		));

		assertThat(inserted.normalizedInserted()).isEqualTo(2);
		assertThat(deleted.canceledSkipped()).isEqualTo(1);
		assertThat(activeTradeCount()).isEqualTo(1);
		assertThat(deletedTradeCount()).isEqualTo(1);

		JdbcMapMarkerRepository mapRepository = new JdbcMapMarkerRepository(jdbcClient);
		assertThat(mapRepository.findComplexMarkers(boundsRequest()))
			.singleElement()
			.extracting(ComplexMarkerResponse::latestDealAmount)
			.isEqualTo(125000L);

		JdbcPropertyReadRepository readRepository = new JdbcPropertyReadRepository(jdbcClient);
		assertThat(readRepository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("dealAmount")
				.containsExactly(125000L));
	}

	private OpenApiTradeIngestService ingestService() {
		return new OpenApiTradeIngestService(
			new JdbcRawTradeIngestRepository(jdbcClient),
			new JdbcNormalizedTradeRepository(jdbcClient, transactionTemplate),
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			new JdbcTradeMatchEvidenceRepository(jdbcClient)
		);
	}

	private void seedExplorationMaster() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type, center_lat, center_lng)
			VALUES (1, '11', 'Seoul', 'si-do', 37.5663, 126.9780)
			""").update();
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473)
			""").update();
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (111, 11, '11680103', 'Sample-dong', 'eup-myeon-dong', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 111, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (
			    id,
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    dong_cnt,
			    unit_cnt,
			    use_date
			)
			VALUES (
			    501,
			    1001,
			    'COMPLEX-PK-501',
			    'APT-501',
			    'Sample Apartment',
			    'Sample trade name',
			    8,
			    740,
			    DATE '2015-03-20'
			)
			""").update();
	}

	private OpenApiTradeItem rtmsItem(
		String aptSeq,
		String aptName,
		int dealDay,
		String dealAmount,
		int floor,
		String jibun,
		String aptDong
	) {
		return new OpenApiTradeItem(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			12,
			2025,
			84.93,
			floor,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"dealDay\":%d,\"dealAmount\":\"%s\",\"jibun\":\"%s\",\"aptDong\":\"%s\"}"
				.formatted(aptSeq, aptName, dealDay, dealAmount, jibun, aptDong)
		);
	}

	private OpenApiTradeItem canceledRtmsItem(
		String aptSeq,
		String aptName,
		int dealDay,
		String dealAmount,
		int floor,
		String jibun,
		String aptDong
	) {
		return new OpenApiTradeItem(
			aptDong,
			aptName,
			aptSeq,
			dealAmount,
			dealDay,
			12,
			2025,
			84.93,
			floor,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"dealDay\":%d,\"dealAmount\":\"%s\",\"jibun\":\"%s\",\"aptDong\":\"%s\",\"cdealType\":\"O\"}"
				.formatted(aptSeq, aptName, dealDay, dealAmount, jibun, aptDong),
			"O",
			"26.03.12",
			null
		);
	}

	private ComplexMarkersRequest boundsRequest() {
		return new ComplexMarkersRequest(
			37.45,
			126.85,
			37.70,
			127.20,
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

	private List<Tuple> rawStatusCounts() {
		return jdbcClient.sql("""
			SELECT status, count(*) AS row_count
			FROM raw_trade_ingest
			GROUP BY status
			ORDER BY status
			""")
			.query((resultSet, rowNumber) -> tuple(resultSet.getString("status"), resultSet.getLong("row_count")))
			.list();
	}

	private List<TradeMatchStatus> evidenceStatuses() {
		return jdbcClient.sql("""
			SELECT match_status
			FROM trade_match_evidence
			ORDER BY id
			""")
			.query((resultSet, rowNumber) -> TradeMatchStatus.valueOf(resultSet.getString("match_status")))
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
