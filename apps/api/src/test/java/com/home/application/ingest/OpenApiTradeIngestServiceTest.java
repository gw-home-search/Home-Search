package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiTradeIngestServiceTest {

	@Test
	@DisplayName("raw ingest는 normalized insert 전에 저장되고 source_key 중복은 normalized trade를 중복 생성하지 않는다")
	void duplicateSourceKeyDoesNotCreateSecondNormalizedTradeAndRawPrecedesInsert() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ")
		);

		OpenApiTradeItem first = rtmsItem("125,000");
		OpenApiTradeItem duplicate = rtmsItem(" 125000 ");

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(first, duplicate)
		));

		assertThat(result.read()).isEqualTo(2);
		assertThat(result.rawSaved()).isEqualTo(2);
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.duplicateSkipped()).isEqualTo(1);
		assertThat(tradeRepository.savedTrades()).hasSize(1);

		String sourceKey = tradeRepository.savedTrades().get(0).sourceKey();
		assertThat(events.indexOf("raw:save:" + sourceKey))
			.isLessThan(events.indexOf("trade:insert:" + sourceKey));
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
	}

	@Test
	@DisplayName("같은 월을 다시 수집하면 기존 거래는 duplicate로 남기고 새 거래만 추가 normalized insert한다")
	void repeatedMonthlyCollectionSkipsExistingTradesAndInsertsOnlyNewTrades() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ")
		);

		IngestResult firstRun = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000", 1))
		));
		IngestResult secondRun = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000", 1), rtmsItem("130,000", 2))
		));

		assertThat(firstRun).isEqualTo(new IngestResult(1, 1, 1, 0, 0, 0));
		assertThat(secondRun).isEqualTo(new IngestResult(2, 2, 1, 1, 0, 0));
		assertThat(tradeRepository.savedTrades())
			.extracting(NormalizedTradeCommand::dealAmount)
			.containsExactly(125000L, 130000L);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(2);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE))
			.singleElement()
			.extracting(RawTradeIngestRecord::failureReason)
			.isEqualTo("duplicate source/source_key");
	}

	@Test
	@DisplayName("fallback identity 중복은 source_key 중복과 다른 raw failure reason으로 남긴다")
	void fallbackIdentityDuplicateUsesSpecificRawFailureReason() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events) {
			@Override
			public boolean insertIfAbsent(NormalizedTradeCommand command) {
				return false;
			}
		};
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ")
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000", 1))
		));

		assertThat(result).isEqualTo(new IngestResult(1, 1, 0, 1, 0, 0));
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE))
			.singleElement()
			.extracting(RawTradeIngestRecord::failureReason)
			.isEqualTo("duplicate fallback identity");
	}

	@Test
	@DisplayName("이미 normalized 된 source_key의 해제 row는 기존 거래를 public 조회에서 제외하고 raw canceled evidence로 남긴다")
	void cancellationRowCancelsExistingNormalizedTrade() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ")
		);

		IngestResult inserted = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000", 1))
		));
		IngestResult canceled = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(canceledRtmsItem("125,000", 1))
		));

		assertThat(inserted).isEqualTo(new IngestResult(1, 1, 1, 0, 0, 0, 0));
		assertThat(canceled).isEqualTo(new IngestResult(1, 1, 0, 0, 1, 0, 0));
		assertThat(tradeRepository.activeTrades()).isEmpty();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.CANCELED))
			.singleElement()
			.extracting(RawTradeIngestRecord::failureReason)
			.isEqualTo("canceled source/source_key");
	}

	@Test
	@DisplayName("complex match 실패는 failure reason과 함께 queryable하게 남는다")
	void matchFailureIsRecordedAsQueryableRawEvidence() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.failed("no complex matched aptSeq=APT-404")
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
		));

		assertThat(result.read()).isEqualTo(1);
		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(tradeRepository.savedTrades()).isEmpty();

		List<RawTradeIngestRecord> failures = rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED);
		assertThat(failures).hasSize(1);
		assertThat(failures.get(0).failureReason()).isEqualTo("no complex matched aptSeq=APT-404");
		assertThat(failures.get(0).sourceKey()).isNotBlank();
	}

	@Test
	@DisplayName("이미 실패 evidence가 있는 source_key 재수집은 duplicate로 남기고 match evidence를 반복 생성하지 않는다")
	void repeatedMatchFailureSourceKeyIsMarkedDuplicateWithoutRepeatedEvidence() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		RecordingTradeMatchEvidenceRepository evidenceRepository = new RecordingTradeMatchEvidenceRepository();
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.failed("no complex matched aptSeq=APT-404"),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);
		OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"))
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
		assertThat(evidenceRepository.savedCommands()).hasSize(1);
	}

	@Test
	@DisplayName("같은 아파트의 서로 다른 failed trade identity는 duplicate로 합치지 않는다")
	void differentFailedTradeIdentityInSameApartmentIsNotMarkedDuplicate() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		RecordingTradeMatchEvidenceRepository evidenceRepository = new RecordingTradeMatchEvidenceRepository();
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.failed("no complex matched aptSeq=APT-501"),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000", 1), rtmsItem("130,000", 2))
		));

		assertThat(result).isEqualTo(new IngestResult(2, 2, 0, 0, 2, 0));
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED)).hasSize(2);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).isEmpty();
		assertThat(evidenceRepository.savedCommands()).hasSize(2);
	}

	@Test
	@DisplayName("같은 source_key여도 payload가 달라진 failed row는 duplicate로 합치지 않고 evidence를 남긴다")
	void changedPayloadWithSameFailedSourceKeyKeepsSeparateEvidence() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		RecordingTradeMatchEvidenceRepository evidenceRepository = new RecordingTradeMatchEvidenceRepository();
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.failed("no complex matched aptSeq=APT-501"),
			ComplexMasterBootstrapper.noop(),
			evidenceRepository
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(
				rtmsItem("125,000", 1, "{\"aptSeq\":\"APT-501\",\"dealDay\":1,\"dealAmount\":\"125,000\"}"),
				rtmsItem("125,000", 1,
					"{\"aptSeq\":\"APT-501\",\"dealDay\":1,\"dealAmount\":\"125,000\",\"cdealType\":\"O\",\"cdealDay\":\"26.03.12\"}")
			)
		));

		assertThat(result).isEqualTo(new IngestResult(2, 2, 0, 0, 2, 0));
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED)).hasSize(2);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).isEmpty();
		assertThat(evidenceRepository.savedCommands()).hasSize(2);
	}

	@Test
	@DisplayName("parse 실패는 normalized trade insert 없이 raw evidence로 queryable하게 남는다")
	void parseFailureIsRecordedAsQueryableRawEvidence() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ")
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem(null))
		));

		assertThat(result.read()).isEqualTo(1);
		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.parseFailed()).isEqualTo(1);
		assertThat(result.normalizedInserted()).isZero();
		assertThat(tradeRepository.savedTrades()).isEmpty();

		List<RawTradeIngestRecord> failures = rawRepository.findByStatus(RawTradeIngestStatus.PARSE_FAILED);
		assertThat(failures).hasSize(1);
		assertThat(failures.get(0).failureReason()).isEqualTo("dealAmount is required");
		assertThat(failures.get(0).sourceKey()).isNotBlank();
	}

	@Test
	@DisplayName("ingest는 completed batch count summary를 metrics에 기록한다")
	void ingestRecordsCompletedBatchCountSummaryToMetrics() {
		List<String> events = new ArrayList<>();
		RecordingRawTradeIngestRepository rawRepository = new RecordingRawTradeIngestRepository(events);
		RecordingNormalizedTradeRepository tradeRepository = new RecordingNormalizedTradeRepository(events);
		RecordingTradeIngestMetrics metrics = new RecordingTradeIngestMetrics();
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "APTSEQ"),
			ComplexMasterBootstrapper.noop(),
			metrics
		);

		OpenApiTradeItem first = rtmsItem("125,000");
		OpenApiTradeItem duplicate = rtmsItem(" 125000 ");

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(first, duplicate)
		));

		assertThat(metrics.recordedSources()).containsExactly("RTMS");
		assertThat(metrics.recordedResults()).containsExactly(result);
	}

	private OpenApiTradeItem rtmsItem(String dealAmount) {
		return rtmsItem(dealAmount, 1);
	}

	private OpenApiTradeItem rtmsItem(String dealAmount, int dealDay) {
		return rtmsItem(dealAmount, dealDay,
			"{\"aptSeq\":\"APT-501\",\"dealDay\":%d,\"dealAmount\":\"%s\"}".formatted(dealDay, dealAmount));
	}

	private OpenApiTradeItem rtmsItem(String dealAmount, int dealDay, String payload) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			dealAmount,
			dealDay,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			payload
		);
	}

	private OpenApiTradeItem canceledRtmsItem(String dealAmount, int dealDay) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			dealAmount,
			dealDay,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-501\",\"dealDay\":%d,\"dealAmount\":\"%s\",\"cdealType\":\"O\",\"cdealDay\":\"26.03.12\"}"
				.formatted(dealDay, dealAmount),
			"O",
			"26.03.12",
			null
		);
	}

	private static final class RecordingRawTradeIngestRepository implements RawTradeIngestRepository {
		private final List<String> events;
		private final Map<Long, RawTradeIngestRecord> records = new LinkedHashMap<>();
		private long nextId = 1L;

		private RecordingRawTradeIngestRepository(List<String> events) {
			this.events = events;
		}

		@Override
		public RawTradeIngestRecord save(RawTradeIngestRecord record) {
			RawTradeIngestRecord saved = record.withId(nextId++);
			records.put(saved.id(), saved);
			events.add("raw:save:" + saved.sourceKey());
			return saved;
		}

		@Override
		public RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason) {
			RawTradeIngestRecord updated = Optional.ofNullable(records.get(id))
				.orElseThrow()
				.withStatus(status, failureReason);
			records.put(id, updated);
			events.add("raw:update:" + status + ":" + updated.sourceKey());
			return updated;
		}

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
			return records.values().stream()
				.filter(record -> record.status() == status)
				.toList();
		}

		@Override
		public boolean existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
			Long rawIngestId,
			String source,
			String sourceKey,
			String payloadHash
		) {
			return records.values().stream()
				.anyMatch(record -> record.id() < rawIngestId
					&& record.source().equals(source)
					&& record.sourceKey().equals(sourceKey)
					&& record.payloadHash().equals(payloadHash)
					&& record.status() != RawTradeIngestStatus.RECEIVED);
		}

		@Override
		public List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query) {
			return List.of();
		}
	}

	private static class RecordingNormalizedTradeRepository implements NormalizedTradeRepository {
		private final List<String> events;
		private final Map<String, NormalizedTradeCommand> tradesBySourceKey = new LinkedHashMap<>();

		private RecordingNormalizedTradeRepository(List<String> events) {
			this.events = events;
		}

		@Override
		public boolean existsBySourceAndSourceKey(String source, String sourceKey) {
			return tradesBySourceKey.containsKey(key(source, sourceKey));
		}

		@Override
		public boolean insertIfAbsent(NormalizedTradeCommand command) {
			String key = key(command.source(), command.sourceKey());
			if (tradesBySourceKey.containsKey(key)) {
				events.add("trade:duplicate:" + command.sourceKey());
				return false;
			}
			tradesBySourceKey.put(key, command);
			events.add("trade:insert:" + command.sourceKey());
			return true;
		}

		@Override
		public boolean cancelBySourceAndSourceKey(String source, String sourceKey, Long rawIngestId) {
			return tradesBySourceKey.remove(key(source, sourceKey)) != null;
		}

		private List<NormalizedTradeCommand> savedTrades() {
			return List.copyOf(tradesBySourceKey.values());
		}

		private List<NormalizedTradeCommand> activeTrades() {
			return savedTrades();
		}

		private String key(String source, String sourceKey) {
			return source + "|" + sourceKey;
		}
	}

	private static final class RecordingTradeIngestMetrics implements TradeIngestMetrics {
		private final List<IngestResult> recordedResults = new ArrayList<>();
		private final List<String> recordedSources = new ArrayList<>();

		@Override
		public void record(String source, IngestResult result) {
			recordedSources.add(source);
			recordedResults.add(result);
		}

		private List<String> recordedSources() {
			return recordedSources;
		}

		private List<IngestResult> recordedResults() {
			return recordedResults;
		}
	}

	private static final class RecordingTradeMatchEvidenceRepository implements TradeMatchEvidenceRepository {
		private final List<TradeMatchEvidenceCommand> savedCommands = new ArrayList<>();

		@Override
		public TradeMatchEvidenceRecord save(TradeMatchEvidenceCommand command) {
			savedCommands.add(command);
			return null;
		}

		@Override
		public Optional<TradeMatchEvidenceRecord> findByRawIngestId(Long rawIngestId) {
			return Optional.empty();
		}

		private List<TradeMatchEvidenceCommand> savedCommands() {
			return savedCommands;
		}
	}
}
