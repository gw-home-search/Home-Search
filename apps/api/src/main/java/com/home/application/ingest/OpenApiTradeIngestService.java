package com.home.application.ingest;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Open API trade item을 raw evidence로 먼저 저장한 뒤 complex match와 normalized trade insert를 수행하는 ingest service입니다.
 */
public class OpenApiTradeIngestService {

	private final RawTradeIngestRepository rawTradeIngestRepository;
	private final NormalizedTradeRepository normalizedTradeRepository;
	private final ComplexMatcher complexMatcher;
	private final ComplexMasterBootstrapper complexMasterBootstrapper;
	private final SourceKeyGenerator sourceKeyGenerator;
	private final TradeIngestMetrics tradeIngestMetrics;

	public OpenApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher
	) {
		this(rawTradeIngestRepository, normalizedTradeRepository, complexMatcher, ComplexMasterBootstrapper.noop());
	}

	public OpenApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper
	) {
		this(rawTradeIngestRepository, normalizedTradeRepository, complexMatcher, complexMasterBootstrapper,
			TradeIngestMetrics.noop());
	}

	public OpenApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeIngestMetrics tradeIngestMetrics
	) {
		this(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			new SourceKeyGenerator(),
			tradeIngestMetrics
		);
	}

	OpenApiTradeIngestService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		SourceKeyGenerator sourceKeyGenerator,
		TradeIngestMetrics tradeIngestMetrics
	) {
		this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
		this.normalizedTradeRepository = Objects.requireNonNull(normalizedTradeRepository);
		this.complexMatcher = Objects.requireNonNull(complexMatcher);
		this.complexMasterBootstrapper = Objects.requireNonNull(complexMasterBootstrapper);
		this.sourceKeyGenerator = Objects.requireNonNull(sourceKeyGenerator);
		this.tradeIngestMetrics = Objects.requireNonNull(tradeIngestMetrics);
	}

	/**
	 * batch의 각 item을 raw row로 보존하고, parse/match/dedupe 결과에 따라 raw status와 normalized trade를 갱신합니다.
	 *
	 * @param batch live Open API 호출 없이 준비된 수집 batch
	 * @return raw 저장, normalized insert, duplicate, 실패 count
	 */
	public IngestResult ingest(OpenApiTradeIngestBatch batch) {
		long rawSaved = 0;
		long normalizedInserted = 0;
		long duplicateSkipped = 0;
		long matchFailed = 0;
		long parseFailed = 0;

		for (OpenApiTradeItem item : batch.items()) {
			String sourceKey = sourceKeyGenerator.generate(batch.source(), item);
			RawTradeIngestRecord raw = rawTradeIngestRepository.save(RawTradeIngestRecord.received(
				batch.source(),
				sourceKey,
				batch.lawdCd(),
				batch.dealYmd(),
				batch.pageNo(),
				item.payload(),
				sourceKeyGenerator.hashPayload(item.payload())
			));
			rawSaved++;

			if (normalizedTradeRepository.existsBySourceAndSourceKey(batch.source(), sourceKey)) {
				rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.DUPLICATE,
					"duplicate source/source_key");
				duplicateSkipped++;
				continue;
			}

			ParsedTrade parsedTrade;
			try {
				parsedTrade = ParsedTrade.from(item);
			}
			catch (IllegalArgumentException exception) {
				rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.PARSE_FAILED,
					exception.getMessage());
				parseFailed++;
				continue;
			}

			ComplexMasterBootstrapResult bootstrapResult = complexMasterBootstrapper.bootstrap(item);
			ComplexMatchResult match = complexMatcher.match(item);
			if (match == null || !match.matched()) {
				rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.MATCH_FAILED,
					matchFailureReason(match, bootstrapResult));
				matchFailed++;
				continue;
			}

			NormalizedTradeCommand command = new NormalizedTradeCommand(
				raw.id(),
				match.complexId(),
				parsedTrade.dealDate(),
				parsedTrade.dealAmount(),
				parsedTrade.floor(),
				item.exclArea(),
				item.aptDong(),
				batch.source(),
				sourceKey,
				match.complexPk(),
				item.aptSeq()
			);

			if (normalizedTradeRepository.insertIfAbsent(command)) {
				rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.NORMALIZED, null);
				normalizedInserted++;
			}
			else {
				rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.DUPLICATE,
					"duplicate source/source_key");
				duplicateSkipped++;
			}
		}

		IngestResult result = new IngestResult(
			batch.items().size(),
			rawSaved,
			normalizedInserted,
			duplicateSkipped,
			matchFailed,
			parseFailed
		);
		tradeIngestMetrics.record(batch.source(), result);
		return result;
	}

	private String matchFailureReason(ComplexMatchResult match, ComplexMasterBootstrapResult bootstrapResult) {
		String matchFailure = match == null ? "complex matcher returned no result" : match.failureReason();
		if (bootstrapResult == null || !bootstrapResult.hasFailureReason()) {
			return matchFailure;
		}
		if (matchFailure == null || matchFailure.isBlank()) {
			return bootstrapResult.failureReason();
		}
		if (matchFailure.contains(bootstrapResult.failureReason())) {
			return matchFailure;
		}
		return matchFailure + "; " + bootstrapResult.failureReason();
	}

	private record ParsedTrade(
		LocalDate dealDate,
		Long dealAmount,
		Integer floor
	) {

		private static ParsedTrade from(OpenApiTradeItem item) {
			try {
				LocalDate dealDate = LocalDate.of(item.dealYear(), item.dealMonth(), item.dealDay());
				Long dealAmount = parseDealAmount(item.dealAmount());
				Integer floor = item.floor() != null && item.floor() == 0 ? null : item.floor();
				return new ParsedTrade(dealDate, dealAmount, floor);
			}
			catch (DateTimeException | NullPointerException exception) {
				throw new IllegalArgumentException("invalid deal date", exception);
			}
		}

		private static Long parseDealAmount(String rawAmount) {
			if (rawAmount == null || rawAmount.isBlank()) {
				throw new IllegalArgumentException("dealAmount is required");
			}
			try {
				long amount = Long.parseLong(rawAmount.replace(",", "").replaceAll("\\s+", ""));
				if (amount <= 0) {
					throw new IllegalArgumentException("dealAmount must be positive");
				}
				return amount;
			}
			catch (NumberFormatException exception) {
				throw new IllegalArgumentException("dealAmount must be numeric", exception);
			}
		}
	}
}
