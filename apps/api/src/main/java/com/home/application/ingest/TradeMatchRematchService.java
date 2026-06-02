package com.home.application.ingest;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;

public class TradeMatchRematchService {

	private static final String SOURCE_KEY_DUPLICATE_REASON = "rematch duplicate source/source_key";
	private static final String FALLBACK_IDENTITY_DUPLICATE_REASON = "rematch duplicate fallback identity";

	private final RawTradeIngestRepository rawTradeIngestRepository;
	private final NormalizedTradeRepository normalizedTradeRepository;
	private final ComplexMatcher complexMatcher;
	private final ComplexMasterBootstrapper complexMasterBootstrapper;
	private final TradeMatchEvidenceRepository tradeMatchEvidenceRepository;
	private final RawTradeItemParser rawTradeItemParser;

	public TradeMatchRematchService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository,
		RawTradeItemParser rawTradeItemParser
	) {
		this(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			ComplexMasterBootstrapper.noop(),
			tradeMatchEvidenceRepository,
			rawTradeItemParser
		);
	}

	public TradeMatchRematchService(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository,
		RawTradeItemParser rawTradeItemParser
	) {
		this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
		this.normalizedTradeRepository = Objects.requireNonNull(normalizedTradeRepository);
		this.complexMatcher = Objects.requireNonNull(complexMatcher);
		this.complexMasterBootstrapper = Objects.requireNonNull(complexMasterBootstrapper);
		this.tradeMatchEvidenceRepository = Objects.requireNonNull(tradeMatchEvidenceRepository);
		this.rawTradeItemParser = Objects.requireNonNull(rawTradeItemParser);
	}

	public TradeMatchRematchResult rematchHeld(int limit) {
		if (limit <= 0) {
			return TradeMatchRematchResult.empty();
		}
		TradeMatchRematchResult result = TradeMatchRematchResult.empty();
		for (RawTradeIngestRecord raw : rawTradeIngestRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED, limit)) {
			result = rematchOne(raw, result);
		}
		return result;
	}

	private TradeMatchRematchResult rematchOne(RawTradeIngestRecord raw, TradeMatchRematchResult result) {
		OpenApiTradeItem item = rawTradeItemParser.parse(raw).orElse(null);
		if (item == null) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.PARSE_FAILED,
				"rematch raw payload cannot be parsed");
			return result.plusParseFailed();
		}
		if (item.isCanceled()) {
			return result.plusSkipped();
		}
		if (normalizedTradeRepository.existsBySourceAndSourceKey(raw.source(), raw.sourceKey())) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.DUPLICATE,
				SOURCE_KEY_DUPLICATE_REASON);
			return result.plusDuplicate();
		}

		ParsedTrade parsedTrade;
		try {
			parsedTrade = ParsedTrade.from(item);
		}
		catch (IllegalArgumentException exception) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.PARSE_FAILED, exception.getMessage());
			return result.plusParseFailed();
		}

		ComplexMasterBootstrapResult bootstrapResult = complexMasterBootstrapper.bootstrap(item);
		ComplexMatchResult match = complexMatcher.match(item);
		tradeMatchEvidenceRepository.save(TradeMatchEvidenceCommand.from(raw.id(), raw.source(), item, match));
		if (match == null || !match.matched()) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.MATCH_FAILED,
				rematchFailureReason(match, bootstrapResult));
			return result.plusStillFailed();
		}

		NormalizedTradeCommand command = new NormalizedTradeCommand(
			raw.id(),
			match.complexId(),
			parsedTrade.dealDate(),
			parsedTrade.dealAmount(),
			parsedTrade.floor(),
			TradeExclAreaNormalizer.normalizeToDouble(item.exclArea()),
			item.aptDong(),
			raw.source(),
			raw.sourceKey(),
			match.complexPk(),
			item.aptSeq()
		);
		if (normalizedTradeRepository.insertIfAbsent(command)) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.NORMALIZED, null);
			return result.plusNormalized();
		}
		rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestStatus.DUPLICATE,
			FALLBACK_IDENTITY_DUPLICATE_REASON);
		return result.plusDuplicate();
	}

	private String rematchFailureReason(ComplexMatchResult match, ComplexMasterBootstrapResult bootstrapResult) {
		String reason = match == null ? "complex matcher returned no result" : match.failureReason();
		String failureReason = reason == null || reason.isBlank() ? "rematch failed" : "rematch failed: " + reason;
		if (bootstrapResult == null || !bootstrapResult.hasFailureReason()) {
			return failureReason;
		}
		if (failureReason.contains(bootstrapResult.failureReason())) {
			return failureReason;
		}
		return failureReason + "; " + bootstrapResult.failureReason();
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
