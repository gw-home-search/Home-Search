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
	@DisplayName("raw ingest is saved before normalized insert and source_key duplicates do not create duplicate trades")
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
	@DisplayName("complex match failures remain queryable with failure reason")
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
	}

	private static final class RecordingNormalizedTradeRepository implements NormalizedTradeRepository {
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

		private List<NormalizedTradeCommand> savedTrades() {
			return List.copyOf(tradesBySourceKey.values());
		}

		private String key(String source, String sourceKey) {
			return source + "|" + sourceKey;
		}
	}
}
