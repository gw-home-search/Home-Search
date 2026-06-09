package com.home.application.ingest.matching;

import java.util.Objects;
import com.home.application.ingest.raw.RawTradeItemParser;
import com.home.application.ingest.normalization.NormalizedTradeCommand;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.application.ingest.trade.ParsedRtmsTrade;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.domain.ingest.raw.RawTradeIngestTransition;
import com.home.domain.trade.TradeExclAreaNormalizer;

public class TradeMatchRematchService {

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
			rawTradeIngestRepository.updateStatus(
				raw.id(),
				RawTradeIngestTransition.parseFailed("rematch raw payload cannot be parsed")
			);
			return result.plusParseFailed();
		}
		if (item.isCanceled()) {
			return result.plusSkipped();
		}
		if (normalizedTradeRepository.existsBySourceAndSourceKey(raw.source(), raw.sourceKey())) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.rematchSourceKeyDuplicate());
			return result.plusDuplicate();
		}

		ParsedRtmsTrade parsedTrade;
		try {
			parsedTrade = ParsedRtmsTrade.from(item);
		}
		catch (IllegalArgumentException exception) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.parseFailed(exception.getMessage()));
			return result.plusParseFailed();
		}

		ComplexMasterBootstrapResult bootstrapResult = complexMasterBootstrapper.bootstrap(item);
		ComplexMatchResult match = complexMatcher.match(item);
		tradeMatchEvidenceRepository.save(TradeMatchEvidenceCommand.from(raw.id(), raw.source(), item, match));
		if (match == null || !match.matched()) {
			rawTradeIngestRepository.updateStatus(
				raw.id(),
				RawTradeIngestTransition.matchFailed(rematchFailureReason(match, bootstrapResult))
			);
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
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.normalized());
			return result.plusNormalized();
		}
		rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.rematchFallbackIdentityDuplicate());
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
}
