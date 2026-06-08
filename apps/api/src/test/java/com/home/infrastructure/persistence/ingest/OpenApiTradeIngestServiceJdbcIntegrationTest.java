package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.home.application.ingest.matching.ComplexMasterBootstrapper;
import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestStatus;
import com.home.application.ingest.matching.TradeMatchEvidenceRecord;
import com.home.application.ingest.matching.TradeMatchStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiTradeIngestServiceJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("raw row는 normalized insert 전에 저장되고 duplicate raw row는 queryable하게 남는다")
	void rawRowsPrecedeNormalizedInsertAndDuplicateRawRowsRemainQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			evidenceRepository
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
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
		assertThat(evidence.matchPath()).isEqualTo("APTSEQ");
		assertThat(evidence.derivedPnu()).isEqualTo("1168010300101400001");
		assertThat(evidence.candidateCount()).isEqualTo(1);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L);
	}

	@Test
	@DisplayName("local PostGIS matcher는 PNU_CONFLICT RTMS match를 queryable하게 유지한다")
	void localPostgisMatcherKeepsPnuConflictRtmsMatchesQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(new OpenApiTradeItem(
				"101",
				"Conflicting Apartment",
				"APT-501",
				"125,000",
				1,
				12,
				2025,
				84.93,
				12,
				"999-1",
				"11680",
				"10300",
				"{\"aptSeq\":\"APT-501\",\"jibun\":\"999-1\"}"
			))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED))
			.singleElement()
			.satisfies(record -> {
				assertThat(record.source()).isEqualTo("RTMS");
				assertThat(record.failureReason()).contains("APT-501");
				assertThat(record.failureReason()).contains("1168010300109990001");
			});
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.PNU_CONFLICT);
		assertThat(evidence.derivedPnu()).isEqualTo("1168010300109990001");
		assertThat(evidence.candidateCount()).isEqualTo(1);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L);
	}

	@Test
	@DisplayName("같은 source_key의 failed RTMS match 재수집은 duplicate raw로 남기고 match evidence를 반복 생성하지 않는다")
	void repeatedFailedRtmsMatchSourceKeyBecomesDuplicateWithoutRepeatedEvidence() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			evidenceRepository
		);
		OpenApiTradeItem pnuConflictItem = new OpenApiTradeItem(
			"101",
			"Conflicting Apartment",
			"APT-501",
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			"999-1",
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-501\",\"jibun\":\"999-1\"}"
		);
		OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(pnuConflictItem)
		);

		IngestResult first = service.ingest(batch);
		IngestResult second = service.ingest(batch);

		assertThat(first).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0));
		assertThat(second).isEqualTo(new IngestResult(1, 1, 0, 1, 0, 0));
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE))
			.singleElement()
			.extracting(RawTradeIngestRecord::failureReason)
			.isEqualTo("duplicate source/source_key");
		assertThat(tradeCount()).isZero();
		assertThat(tradeMatchEvidenceCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("RTMS source_key가 다른 동일 조건 거래는 aptDong이 다르면 각각 normalized trade로 보존된다")
	void ingestKeepsSameConditionTradesSeparateWhenAptDongDiffers() {
		seedComplex();
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
			List.of(
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "101"),
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "102")
			)
		));

		assertThat(result.rawSaved()).isEqualTo(2);
		assertThat(result.normalizedInserted()).isEqualTo(2);
		assertThat(result.duplicateSkipped()).isZero();
		assertThat(tradeCount()).isEqualTo(2);
		assertThat(activeAptDongs()).containsExactly("101", "102");
	}

	@Test
	@DisplayName("RTMS aptDong이 누락된 거래와 보강된 거래는 fallback duplicate로 처리된다")
	void ingestTreatsMissingAndFilledAptDongAsFallbackDuplicate() {
		seedComplex();
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
			List.of(
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "   "),
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "101")
			)
		));

		assertThat(result.rawSaved()).isEqualTo(2);
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.duplicateSkipped()).isEqualTo(1);
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(activeAptDongs()).containsExactly((String) null);
	}

	@Test
	@DisplayName("RTMS aptDong 없는 row가 복수 동 후보에 걸리면 duplicate로 남기되 기존 거래를 연결하지 않는다")
	void ingestDoesNotAttachMissingAptDongToAmbiguousDongCandidates() {
		seedComplex();
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
			List.of(
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "101"),
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "102"),
				liveRtmsItem("APT-501", "Sample Apartment", "140-1", "   ")
			)
		));

		assertThat(result.rawSaved()).isEqualTo(3);
		assertThat(result.normalizedInserted()).isEqualTo(2);
		assertThat(result.duplicateSkipped()).isEqualTo(1);
		assertThat(tradeCount()).isEqualTo(2);
		assertThat(activeAptDongs()).containsExactly("101", "102");
		assertThat(registryTradeIds()).containsExactlyInAnyOrder(onlyTradeId("101"), onlyTradeId("102"), null);
	}

	@Test
	@DisplayName("normalized 이후 도착한 RTMS 해제 row는 기존 trade를 public 조회에서 제외하고 canceled raw로 남긴다")
	void cancellationRowAfterNormalizedTradeMarksTradeDeleted() {
		seedComplex();
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

		IngestResult inserted = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
		));
		IngestResult canceled = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(canceledRtmsItem("125,000"))
		));

		assertThat(inserted).isEqualTo(new IngestResult(1, 1, 1, 0, 0, 0, 0));
		assertThat(canceled).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0, 0));
		assertThat(activeTradeCount()).isZero();
		assertThat(deletedTradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.CANCELED))
			.singleElement()
			.extracting(RawTradeIngestRecord::failureReason)
			.isEqualTo("canceled source/source_key");
	}

	@Test
	@DisplayName("RTMS 해제 row가 먼저 오면 source_key registry를 선점해 이후 정상 row도 public trade로 만들지 않는다")
	void cancellationRowBeforeNormalizedTradePreventsLaterInsert() {
		seedComplex();
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

		IngestResult canceled = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(canceledRtmsItem("125,000"))
		));
		IngestResult duplicate = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
		));

		assertThat(canceled).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0, 0));
		assertThat(duplicate).isEqualTo(new IngestResult(1, 1, 0, 1, 0, 0, 0));
		assertThat(tradeCount()).isZero();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.CANCELED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
	}

	@Test
	@DisplayName("RTMS 해제 이후 같은 source_key 정상 row가 다시 와도 terminal cancellation 정책상 revive하지 않는다")
	void activeRowAfterCancellationDoesNotReviveTerminalCanceledTrade() {
		seedComplex();
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

		IngestResult inserted = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
		));
		IngestResult canceled = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(canceledRtmsItem("125,000"))
		));
		IngestResult reappeared = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
		));

		assertThat(inserted).isEqualTo(new IngestResult(1, 1, 1, 0, 0, 0, 0));
		assertThat(canceled).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0, 0));
		assertThat(reappeared).isEqualTo(new IngestResult(1, 1, 0, 1, 0, 0, 0));
		assertThat(activeTradeCount()).isZero();
		assertThat(deletedTradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.CANCELED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
	}

	@Test
	@DisplayName("live RTMS row는 coordinate가 resolve되면 normalized insert 전에 parcel/complex master를 bootstrap한다")
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
		assertThat(complexAliasCount("APT-LIVE-501", "RTMS_APT_NAME", "livesampleapartment")).isEqualTo(1);
	}

	@Test
	@DisplayName("RTMS aptNm은 기존 complex master 이름을 덮어쓰지 않고 alias로 보존된다")
	void recordsRtmsObservedNameAsAliasWithoutOverwritingMasterNames() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Official Trade Name'
			WHERE apt_seq = 'APT-501'
			""").update();
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
			List.of(liveRtmsItem("APT-501", "RTMS Wobbly Name", "140-1"))
		));

		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(complexMasterNames("APT-501"))
			.containsEntry("name", "Building Register Name")
			.containsEntry("trade_name", "Official Trade Name");
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "rtmswobblyname")).isEqualTo(1);
	}

	@Test
	@DisplayName("RTMS alias는 중복 aptSeq master에 임의로 붙지 않는다")
	void skipsRtmsAliasWhenAptSeqIsAmbiguous() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-501', 'Other Register Name', 'Other Trade Name', 120)
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			(pnu, item) -> Optional.empty()
		);

		var result = bootstrapper.bootstrap(liveRtmsItem("APT-501", "RTMS Wobbly Name", "140-1"));

		assertThat(result.hasFailureReason()).isTrue();
		assertThat(result.failureReason()).contains("ambiguous aptSeq=APT-501");
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "rtmswobblyname")).isZero();
	}

	@Test
	@DisplayName("live RTMS master bootstrap은 resolver가 제공한 source parcel geometry를 저장한다")
	void bootstrapPersistsSourceParcelGeometryWhenResolverProvidesIt() {
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		String pnu = "1168010300107780001";
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(
				jdbcClient,
				coordinatesWithGeometry(
					pnu,
					"37.5012345",
					"127.0543210",
					"MULTIPOLYGON(((127.0540 37.5010,127.0546 37.5010,127.0546 37.5015,127.0540 37.5015,127.0540 37.5010)))"
				)
			)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-LIVE-502", "Live Geometry Apartment", "778-1"))
		));

		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(parcelGeometryWkt(pnu)).startsWith("MULTIPOLYGON");
	}

	@Test
	@DisplayName("승인된 좌표 override는 Coordinate Source DB miss 이후 live RTMS parcel bootstrap에 사용된다")
	void approvedCoordinateOverrideBootstrapsLiveRtmsParcelAfterCoordinateSourceMiss() {
		String pnu = "1168010300107790001";
		insertCoordinateOverride(
			pnu,
			"APT-LIVE-503",
			"APPROVED",
			"MANUAL",
			"HIGH",
			"37.6112345",
			"127.1643210"
		);
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		ParcelCoordinateResolver resolver = new CoordinateSourceFirstParcelCoordinateResolver(
			pnuCandidate -> Optional.empty(),
			new JdbcParcelCoordinateOverrideRepository(jdbcClient)
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, resolver)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-LIVE-503", "Approved Override Apartment", "779-1"))
		));

		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.matchFailed()).isZero();
		assertThat(parcelCount(pnu)).isEqualTo(1);
		assertThat(parcelCoordinate(pnu))
			.containsEntry("latitude", new BigDecimal("37.6112345"))
			.containsEntry("longitude", new BigDecimal("127.1643210"));
		assertThat(complexCountByAptSeq("APT-LIVE-503")).isEqualTo(1);
	}

	@Test
	@DisplayName("주소 검색 좌표 후보는 승인 전 live RTMS parcel 좌표로 사용하지 않지만 identity 저장은 허용한다")
	void coordinateCandidateDoesNotProvideCoordinateButIdentityStillBootstrapsBeforeApproval() {
		String pnu = "1168010300108890001";
		insertCoordinateOverride(
			pnu,
			"APT-LIVE-889",
			"CANDIDATE",
			"VWORLD_ADDRESS_SEARCH",
			"LOW",
			"37.7112345",
			"127.2643210"
		);
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		ParcelCoordinateResolver resolver = new CoordinateSourceFirstParcelCoordinateResolver(
			pnuCandidate -> Optional.empty(),
			new JdbcParcelCoordinateOverrideRepository(jdbcClient)
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, resolver)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-LIVE-889", "Candidate Coordinate Apartment", "889-1"))
		));

		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.matchFailed()).isZero();
		assertThat(parcelCount(pnu)).isEqualTo(1);
		assertThat(parcelCoordinate(pnu))
			.containsEntry("latitude", null)
			.containsEntry("longitude", null);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
	}

	@Test
	@DisplayName("coordinate가 resolve되지 않은 live RTMS row는 좌표 없는 parcel shell로 저장되고 normalized trade가 된다")
	void coordinateMissingStoresCoordinatePendingParcelShellAndNormalizedTrade() {
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
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.matchFailed()).isZero();
		assertThat(parcelCount("1168010300108880001")).isEqualTo(1);
		assertThat(parcelCoordinate("1168010300108880001"))
			.containsEntry("latitude", null)
			.containsEntry("longitude", null);
		assertThat(complexCountByAptSeq("APT-LIVE-404")).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
	}

	@Test
	@DisplayName("aptSeq가 unique여도 derived PNU가 complex parcel과 다르면 normalized trade를 만들지 않고 PNU_CONFLICT evidence로 보류한다")
	void holdsAptSeqMatchWhenDerivedPnuConflictsWithComplexParcel() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-501", "Conflicting Alias Name", "999-1"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.PNU_CONFLICT);
		assertThat(evidence.derivedPnu()).isEqualTo("1168010300109990001");
		assertThat(evidence.candidateCount()).isEqualTo(1);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L);
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "conflictingaliasname")).isZero();
	}

	@Test
	@DisplayName("PNU 단일 후보라도 RTMS 이름이 master/alias와 다르면 NAME_CONFLICT evidence로 보류한다")
	void holdsSinglePnuCandidateWhenNameConflicts() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem(null, "Different Apartment", "140-1"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.NAME_CONFLICT);
		assertThat(evidence.candidateCount()).isEqualTo(1);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L);
	}

	@Test
	@DisplayName("aptSeq unique와 PNU가 일치하면 RTMS 이름 variant를 alias/evidence로 남기고 normalized trade를 허용한다")
	void insertsAptSeqMatchWithNameVariantAsEvidenceAndAlias() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			new JdbcComplexMasterBootstrapper(jdbcClient, (pnu, item) -> Optional.empty()),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem("APT-501", "Observed Sample Tower", "140-1"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.matchFailed()).isZero();
		assertThat(tradeCount()).isEqualTo(1);
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED_NAME_VARIANT);
		assertThat(evidence.matchPath()).isEqualTo("APTSEQ");
		assertThat(evidence.matchedComplexId()).isEqualTo(501L);
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "observedsampletower")).isEqualTo(1);
		assertThat(complexMasterNames("APT-501")).containsEntry("name", "Sample Apartment");
	}

	@Test
	@DisplayName("PNU 후보가 여러 개이고 이름 evidence가 없으면 AMBIGUOUS evidence로 보류한다")
	void holdsAmbiguousPnuCandidatesWithEvidence() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Other Apartment', 'Other trade name', 120)
			""").update();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem(null, "Unknown Apartment", "140-1"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.AMBIGUOUS);
		assertThat(evidence.candidateCount()).isEqualTo(2);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L, 502L);
	}

	@Test
	@DisplayName("PNU를 만들 수 없는 RTMS 지번은 PNU_UNAVAILABLE evidence로 보류한다")
	void holdsPnuUnavailableRowsWithEvidence() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(liveRtmsItem(null, "Sample Apartment", "번지없음"))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		TradeMatchEvidenceRecord evidence = firstEvidence(evidenceRepository);
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.PNU_UNAVAILABLE);
		assertThat(evidence.derivedPnu()).isNull();
		assertThat(evidence.pnuUnavailableReason()).isEqualTo("invalid jibun");
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

	private OpenApiTradeItem canceledRtmsItem(String dealAmount) {
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
			"{\"aptSeq\":\"APT-501\",\"dealAmount\":\"%s\",\"cdealType\":\"O\",\"cdealDay\":\"26.03.12\"}"
				.formatted(dealAmount),
			"O",
			"26.03.12",
			null
		);
	}

	private OpenApiTradeItem liveRtmsItem(String aptSeq, String aptName, String jibun) {
		return liveRtmsItem(aptSeq, aptName, jibun, "101");
	}

	private OpenApiTradeItem liveRtmsItem(String aptSeq, String aptName, String jibun, String aptDong) {
		return new OpenApiTradeItem(
			aptDong,
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
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"jibun\":\"%s\",\"aptDong\":\"%s\"}"
				.formatted(aptSeq, aptName, jibun, aptDong)
		);
	}

	private ParcelCoordinateResolver coordinates(String expectedPnu, String latitude, String longitude) {
		ParcelCoordinate coordinate = new ParcelCoordinate(new BigDecimal(latitude), new BigDecimal(longitude));
		return (pnu, item) -> expectedPnu.equals(pnu) ? Optional.of(coordinate) : Optional.empty();
	}

	private ParcelCoordinateResolver coordinatesWithGeometry(
		String expectedPnu,
		String latitude,
		String longitude,
		String geometryWkt
	) {
		ParcelCoordinate coordinate = new ParcelCoordinate(
			new BigDecimal(latitude),
			new BigDecimal(longitude),
			geometryWkt
		);
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

	private String parcelGeometryWkt(String pnu) {
		return jdbcClient.sql("""
			SELECT ST_AsText(geom)
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query(String.class)
			.single();
	}

	private java.util.Map<String, Object> parcelCoordinate(String pnu) {
		return jdbcClient.sql("""
			SELECT latitude, longitude
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("latitude", resultSet.getBigDecimal("latitude"));
				row.put("longitude", resultSet.getBigDecimal("longitude"));
				return row;
			})
			.single();
	}

	private void insertCoordinateOverride(
		String pnu,
		String aptSeq,
		String status,
		String source,
		String confidence,
		String latitude,
		String longitude
	) {
		jdbcClient.sql("""
			INSERT INTO parcel_coordinate_override (
			    pnu,
			    apt_seq,
			    apt_name,
			    address_text,
			    latitude,
			    longitude,
			    source,
			    confidence,
			    status,
			    reason,
			    approved_by,
			    approved_at
			)
			VALUES (
			    :pnu,
			    :aptSeq,
			    'Coordinate Override Apartment',
			    'Sample-dong coordinate override',
			    :latitude,
			    :longitude,
			    :source,
			    :confidence,
			    :status,
			    'test coordinate override',
			    CASE WHEN :status = 'APPROVED' THEN 'test-operator' ELSE NULL END,
			    CASE WHEN :status = 'APPROVED' THEN now() ELSE NULL END
			)
			""")
			.param("pnu", pnu)
			.param("aptSeq", aptSeq)
			.param("latitude", new BigDecimal(latitude))
			.param("longitude", new BigDecimal(longitude))
			.param("source", source)
			.param("confidence", confidence)
			.param("status", status)
			.update();
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

	private java.util.Map<String, Object> complexMasterNames(String aptSeq) {
		return jdbcClient.sql("""
			SELECT name, trade_name
			FROM complex
			WHERE apt_seq = :aptSeq
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> values = new HashMap<>();
				values.put("name", resultSet.getString("name"));
				values.put("trade_name", resultSet.getString("trade_name"));
				return values;
			})
			.single();
	}

	private long complexAliasCount(String aptSeq, String aliasType, String normalizedName) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM complex_name_alias a
			JOIN complex c ON c.id = a.complex_id
			WHERE c.apt_seq = :aptSeq
			  AND a.alias_type = :aliasType
			  AND a.normalized_name = :normalizedName
			""")
			.param("aptSeq", aptSeq)
			.param("aliasType", aliasType)
			.param("normalizedName", normalizedName)
			.query(Long.class)
			.single();
	}

	private TradeMatchEvidenceRecord firstEvidence(JdbcTradeMatchEvidenceRepository repository) {
		Long rawIngestId = jdbcClient.sql("SELECT id FROM raw_trade_ingest ORDER BY id LIMIT 1")
			.query(Long.class)
			.single();
		return repository.findByRawIngestId(rawIngestId).orElseThrow();
	}

	private long tradeMatchEvidenceCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade_match_evidence")
			.query(Long.class)
			.single();
	}

	private long activeTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NULL")
			.query(Long.class)
			.single();
	}

	private Long onlyTradeId(String aptDong) {
		return jdbcClient.sql("""
			SELECT id
			FROM trade
			WHERE apt_dong = :aptDong
			""")
			.param("aptDong", aptDong)
			.query(Long.class)
			.single();
	}

	private List<Long> registryTradeIds() {
		return jdbcClient.sql("""
			SELECT COALESCE(trade_id, -1) AS trade_id
			FROM trade_source_key_registry
			ORDER BY source_key
			""")
			.query(Long.class)
			.list()
			.stream()
			.map(tradeId -> tradeId < 0 ? null : tradeId)
			.toList();
	}

	private List<String> activeAptDongs() {
		return jdbcClient.sql("""
			SELECT apt_dong
			FROM trade
			WHERE deleted_at IS NULL
			ORDER BY apt_dong
			""")
			.query(String.class)
			.list();
	}

	private long deletedTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NOT NULL")
			.query(Long.class)
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
