package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TradeMatchRematchServiceTest {

	@Test
	@DisplayName("trade rematch service는 limit이 0 이하면 MATCH_FAILED raw를 조회하지 않는다")
	void returnsEmptyWhenLimitIsNotPositive() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		TradeMatchRematchService service = service(
			rawRepository,
			new FakeNormalizedRepository(),
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(validItem())
		);

		TradeMatchRematchResult result = service.rematchHeld(0);

		assertThat(result).isEqualTo(TradeMatchRematchResult.empty());
		assertThat(rawRepository.findByStatusCalls).isEmpty();
		assertThat(rawRepository.findByStatusLimitCalls).isEmpty();
	}

	@Test
	@DisplayName("trade rematch service는 파싱할 수 없는 raw를 PARSE_FAILED로 남긴다")
	void marksParseFailedWhenRawPayloadCannotBeParsed() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		TradeMatchRematchService service = service(
			rawRepository,
			new FakeNormalizedRepository(),
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.empty()
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.parseFailed()).isEqualTo(1);
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> {
				assertThat(update.status()).isEqualTo(RawTradeIngestStatus.PARSE_FAILED);
				assertThat(update.failureReason()).isEqualTo("rematch raw payload cannot be parsed");
			});
	}

	@Test
	@DisplayName("trade rematch service는 취소 row를 normalized 재생성 없이 skip한다")
	void skipsCanceledRowsWithoutStatusMutation() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		FakeNormalizedRepository normalizedRepository = new FakeNormalizedRepository();
		TradeMatchRematchService service = service(
			rawRepository,
			normalizedRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(canceledItem())
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.skipped()).isEqualTo(1);
		assertThat(rawRepository.updatedStatuses).isEmpty();
		assertThat(normalizedRepository.insertedCommands).isEmpty();
	}

	@Test
	@DisplayName("trade rematch service는 이미 등록된 source key를 DUPLICATE로 남긴다")
	void marksDuplicateWhenSourceKeyAlreadyExists() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		FakeNormalizedRepository normalizedRepository = new FakeNormalizedRepository();
		normalizedRepository.existingSourceKeys.add("RTMS|source-101");
		TradeMatchRematchService service = service(
			rawRepository,
			normalizedRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(validItem())
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.duplicate()).isEqualTo(1);
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> {
				assertThat(update.status()).isEqualTo(RawTradeIngestStatus.DUPLICATE);
				assertThat(update.failureReason()).isEqualTo("rematch duplicate source/source_key");
			});
	}

	@Test
	@DisplayName("trade rematch service는 거래 금액이 유효하지 않으면 PARSE_FAILED로 남긴다")
	void marksParseFailedWhenDealAmountIsInvalid() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		TradeMatchRematchService service = service(
			rawRepository,
			new FakeNormalizedRepository(),
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(itemWithAmount("not-a-number"))
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.parseFailed()).isEqualTo(1);
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> {
				assertThat(update.status()).isEqualTo(RawTradeIngestStatus.PARSE_FAILED);
				assertThat(update.failureReason()).isEqualTo("dealAmount must be numeric");
			});
	}

	@Test
	@DisplayName("trade rematch service는 matcher가 실패하면 evidence를 남기고 MATCH_FAILED를 유지한다")
	void keepsMatchFailedWhenMatcherDoesNotMatch() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		RecordingEvidenceRepository evidenceRepository = new RecordingEvidenceRepository();
		TradeMatchRematchService service = service(
			rawRepository,
			new FakeNormalizedRepository(),
			item -> ComplexMatchResult.failed("parcel has no complex"),
			evidenceRepository,
			raw -> Optional.of(validItem())
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.stillFailed()).isEqualTo(1);
		assertThat(evidenceRepository.savedCommands)
			.singleElement()
			.satisfies(command -> assertThat(command.matchStatus()).isEqualTo(TradeMatchStatus.UNMATCHED));
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> {
				assertThat(update.status()).isEqualTo(RawTradeIngestStatus.MATCH_FAILED);
				assertThat(update.failureReason()).isEqualTo("rematch failed: parcel has no complex");
			});
	}

	@Test
	@DisplayName("trade rematch service는 fallback identity 중복이면 DUPLICATE로 남긴다")
	void marksDuplicateWhenFallbackIdentityAlreadyExists() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(raw(101L, "source-101")));
		FakeNormalizedRepository normalizedRepository = new FakeNormalizedRepository();
		normalizedRepository.insertResult = false;
		TradeMatchRematchService service = service(
			rawRepository,
			normalizedRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(validItem())
		);

		TradeMatchRematchResult result = service.rematchHeld(10);

		assertThat(result.duplicate()).isEqualTo(1);
		assertThat(normalizedRepository.insertedCommands)
			.singleElement()
			.satisfies(command -> {
				assertThat(command.rawIngestId()).isEqualTo(101L);
				assertThat(command.floor()).isEqualTo(12);
				assertThat(command.exclArea()).isEqualTo(84.93);
			});
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> {
				assertThat(update.status()).isEqualTo(RawTradeIngestStatus.DUPLICATE);
				assertThat(update.failureReason()).isEqualTo("rematch duplicate fallback identity");
			});
	}

	@Test
	@DisplayName("trade rematch service는 match와 insert가 성공하면 NORMALIZED로 갱신한다")
	void marksNormalizedWhenInsertSucceeds() {
		FakeRawRepository rawRepository = new FakeRawRepository(List.of(
			raw(101L, "source-101"),
			raw(102L, "source-102")
		));
		FakeNormalizedRepository normalizedRepository = new FakeNormalizedRepository();
		TradeMatchRematchService service = service(
			rawRepository,
			normalizedRepository,
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(itemWithFloor(raw.id() == 101L ? 0 : 7))
		);

		TradeMatchRematchResult result = service.rematchHeld(1);

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.normalized()).isEqualTo(1);
		assertThat(rawRepository.findByStatusLimitCalls)
			.singleElement()
			.satisfies(call -> {
				assertThat(call.status()).isEqualTo(RawTradeIngestStatus.MATCH_FAILED);
				assertThat(call.limit()).isEqualTo(1);
			});
		assertThat(normalizedRepository.insertedCommands)
			.singleElement()
			.satisfies(command -> assertThat(command.floor()).isNull());
		assertThat(rawRepository.updatedStatuses)
			.singleElement()
			.satisfies(update -> assertThat(update.status()).isEqualTo(RawTradeIngestStatus.NORMALIZED));
	}

	private TradeMatchRematchService service(
		RawTradeIngestRepository rawRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		TradeMatchEvidenceRepository evidenceRepository,
		RawTradeItemParser rawTradeItemParser
	) {
		return new TradeMatchRematchService(
			rawRepository,
			normalizedTradeRepository,
			complexMatcher,
			evidenceRepository,
			rawTradeItemParser
		);
	}

	private static RawTradeIngestRecord raw(Long id, String sourceKey) {
		return new RawTradeIngestRecord(
			id,
			"RTMS",
			sourceKey,
			"11680",
			"202512",
			1,
			"{}",
			"hash-" + sourceKey,
			RawTradeIngestStatus.MATCH_FAILED,
			"old failure",
			Instant.parse("2025-12-20T00:00:00Z"),
			null
		);
	}

	private static OpenApiTradeItem validItem() {
		return itemWithFloor(12);
	}

	private static OpenApiTradeItem itemWithFloor(Integer floor) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			"125,000",
			15,
			12,
			2025,
			84.93,
			floor,
			"140-1",
			"11680",
			"10300",
			"{}"
		);
	}

	private static OpenApiTradeItem itemWithAmount(String amount) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			amount,
			15,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{}"
		);
	}

	private static OpenApiTradeItem canceledItem() {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			"125,000",
			15,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{}",
			"O",
			"26.03.12",
			null
		);
	}

	private static final class FakeRawRepository implements RawTradeIngestRepository {
		private final Map<Long, RawTradeIngestRecord> records = new LinkedHashMap<>();
		private final List<StatusUpdate> updatedStatuses = new ArrayList<>();
		private final List<RawTradeIngestStatus> findByStatusCalls = new ArrayList<>();
		private final List<StatusLimitCall> findByStatusLimitCalls = new ArrayList<>();

		private FakeRawRepository(List<RawTradeIngestRecord> records) {
			records.forEach(record -> this.records.put(record.id(), record));
		}

		@Override
		public RawTradeIngestRecord save(RawTradeIngestRecord record) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
			Long rawIngestId,
			String source,
			String sourceKey,
			String payloadHash
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason) {
			updatedStatuses.add(new StatusUpdate(id, status, failureReason));
			RawTradeIngestRecord updated = records.get(id).withStatus(status, failureReason);
			records.put(id, updated);
			return updated;
		}

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
			findByStatusCalls.add(status);
			return records.values().stream()
				.filter(record -> record.status() == status)
				.toList();
		}

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status, int limit) {
			findByStatusLimitCalls.add(new StatusLimitCall(status, limit));
			return records.values().stream()
				.filter(record -> record.status() == status)
				.limit(limit)
				.toList();
		}

		@Override
		public List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class FakeNormalizedRepository implements NormalizedTradeRepository {
		private final List<String> existingSourceKeys = new ArrayList<>();
		private final List<NormalizedTradeCommand> insertedCommands = new ArrayList<>();
		private boolean insertResult = true;

		@Override
		public boolean existsBySourceAndSourceKey(String source, String sourceKey) {
			return existingSourceKeys.contains(source + "|" + sourceKey);
		}

		@Override
		public boolean cancelBySourceAndSourceKey(String source, String sourceKey, Long rawIngestId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean insertIfAbsent(NormalizedTradeCommand command) {
			insertedCommands.add(command);
			return insertResult;
		}
	}

	private static final class RecordingEvidenceRepository implements TradeMatchEvidenceRepository {
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
	}

	private record StatusUpdate(
		Long rawIngestId,
		RawTradeIngestStatus status,
		String failureReason
	) {
	}

	private record StatusLimitCall(
		RawTradeIngestStatus status,
		int limit
	) {
	}
}
