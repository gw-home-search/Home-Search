package com.home.application.ingest.trade;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.matching.ComplexMasterBootstrapResult;
import com.home.application.ingest.matching.ComplexMasterBootstrapper;
import com.home.application.ingest.matching.ComplexMatchResult;
import com.home.application.ingest.matching.ComplexMatcher;
import com.home.application.ingest.matching.TradeMatchEvidenceCommand;
import com.home.application.ingest.matching.TradeMatchEvidenceRepository;
import com.home.application.ingest.normalization.NormalizedTradeCommand;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.domain.ingest.raw.RawTradeIngestTransition;
import com.home.domain.trade.TradeExclAreaNormalizer;

/**
 * Open API trade item 하나를 raw evidence, match evidence, normalized trade 결과로 처리합니다.
 */
public class TradeIngestItemProcessor {

	private final RawTradeIngestRepository rawTradeIngestRepository;
	private final NormalizedTradeRepository normalizedTradeRepository;
	private final ComplexMatcher complexMatcher;
	private final ComplexMasterBootstrapper complexMasterBootstrapper;
	private final SourceKeyGenerator sourceKeyGenerator;
	private final TradeMatchEvidenceRepository tradeMatchEvidenceRepository;

	public TradeIngestItemProcessor(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository
	) {
		this(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			new SourceKeyGenerator(),
			tradeMatchEvidenceRepository
		);
	}

	TradeIngestItemProcessor(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		SourceKeyGenerator sourceKeyGenerator,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository
	) {
		this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
		this.normalizedTradeRepository = Objects.requireNonNull(normalizedTradeRepository);
		this.complexMatcher = Objects.requireNonNull(complexMatcher);
		this.complexMasterBootstrapper = Objects.requireNonNull(complexMasterBootstrapper);
		this.sourceKeyGenerator = Objects.requireNonNull(sourceKeyGenerator);
		this.tradeMatchEvidenceRepository = Objects.requireNonNull(tradeMatchEvidenceRepository);
	}

	public TradeIngestItemOutcome process(OpenApiTradeIngestBatch batch, OpenApiTradeItem item) {
		TradeIdentity identity = identity(batch, item);
		RawTradeIngestRecord raw = saveReceivedRaw(batch, item, identity);

		if (hasProcessedDuplicate(raw, batch, identity)) {
			return sourceKeyDuplicate(raw.id());
		}

		if (item.isCanceled()) {
			return cancelTrade(batch, item, raw.id(), identity.sourceKey());
		}

		if (normalizedTradeRepository.existsBySourceAndSourceKey(batch.source(), identity.sourceKey())) {
			return sourceKeyDuplicate(raw.id());
		}

		Optional<ParsedRtmsTrade> parsedTrade = parseOrMarkFailed(raw.id(), item);
		if (parsedTrade.isEmpty()) {
			return TradeIngestItemOutcome.parseFailed();
		}

		MatchAttempt matchAttempt = matchAndRecordEvidence(raw.id(), batch.source(), item);
		if (!matchAttempt.matched()) {
			return matchFailed(raw.id(), matchAttempt);
		}

		return normalizeTrade(raw.id(), batch, item, identity.sourceKey(), parsedTrade.get(), matchAttempt.match());
	}

	private TradeIdentity identity(OpenApiTradeIngestBatch batch, OpenApiTradeItem item) {
		return new TradeIdentity(
			sourceKeyGenerator.generate(batch.source(), item),
			sourceKeyGenerator.hashPayload(item.payload())
		);
	}

	private RawTradeIngestRecord saveReceivedRaw(
		OpenApiTradeIngestBatch batch,
		OpenApiTradeItem item,
		TradeIdentity identity
	) {
		RawTradeIngestRecord raw = rawTradeIngestRepository.save(RawTradeIngestRecord.received(
			batch.source(),
			identity.sourceKey(),
			batch.lawdCd(),
			batch.dealYmd(),
			batch.pageNo(),
			item.payload(),
			identity.payloadHash()
		));
		return raw;
	}

	private boolean hasProcessedDuplicate(
		RawTradeIngestRecord raw,
		OpenApiTradeIngestBatch batch,
		TradeIdentity identity
	) {
		return rawTradeIngestRepository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
			raw.id(),
			batch.source(),
			identity.sourceKey(),
			identity.payloadHash()
		);
	}

	private TradeIngestItemOutcome cancelTrade(
		OpenApiTradeIngestBatch batch,
		OpenApiTradeItem item,
		long rawId,
		String sourceKey
	) {
		if (parseOrMarkFailed(rawId, item).isEmpty()) {
			return TradeIngestItemOutcome.parseFailed();
		}
		normalizedTradeRepository.cancelBySourceAndSourceKey(batch.source(), sourceKey, rawId);
		rawTradeIngestRepository.updateStatus(rawId, RawTradeIngestTransition.canceledSourceKey());
		return TradeIngestItemOutcome.canceled();
	}

	private Optional<ParsedRtmsTrade> parseOrMarkFailed(long rawId, OpenApiTradeItem item) {
		try {
			return Optional.of(ParsedRtmsTrade.from(item));
		}
		catch (IllegalArgumentException exception) {
			rawTradeIngestRepository.updateStatus(
				rawId,
				RawTradeIngestTransition.parseFailed(exception.getMessage())
			);
			return Optional.empty();
		}
	}

	private MatchAttempt matchAndRecordEvidence(long rawId, String source, OpenApiTradeItem item) {
		ComplexMasterBootstrapResult bootstrapResult = complexMasterBootstrapper.bootstrap(item);
		ComplexMatchResult match = complexMatcher.match(item);
		tradeMatchEvidenceRepository.save(TradeMatchEvidenceCommand.from(rawId, source, item, match));
		return new MatchAttempt(bootstrapResult, match);
	}

	private TradeIngestItemOutcome matchFailed(long rawId, MatchAttempt matchAttempt) {
		rawTradeIngestRepository.updateStatus(
			rawId,
			RawTradeIngestTransition.matchFailed(
				matchFailureReason(matchAttempt.match(), matchAttempt.bootstrapResult())
			)
		);
		return TradeIngestItemOutcome.matchFailed();
	}

	private TradeIngestItemOutcome normalizeTrade(
		long rawId,
		OpenApiTradeIngestBatch batch,
		OpenApiTradeItem item,
		String sourceKey,
		ParsedRtmsTrade parsedTrade,
		ComplexMatchResult match
	) {
		NormalizedTradeCommand command = new NormalizedTradeCommand(
			rawId,
			match.complexId(),
			parsedTrade.dealDate(),
			parsedTrade.dealAmount(),
			parsedTrade.floor(),
			TradeExclAreaNormalizer.normalizeToDouble(item.exclArea()),
			item.aptDong(),
			batch.source(),
			sourceKey,
			match.complexPk(),
			item.aptSeq()
		);

		if (normalizedTradeRepository.insertIfAbsent(command)) {
			rawTradeIngestRepository.updateStatus(rawId, RawTradeIngestTransition.normalized());
			return TradeIngestItemOutcome.normalized();
		}
		rawTradeIngestRepository.updateStatus(rawId, RawTradeIngestTransition.fallbackIdentityDuplicate());
		return TradeIngestItemOutcome.duplicate();
	}

	private TradeIngestItemOutcome sourceKeyDuplicate(long rawId) {
		rawTradeIngestRepository.updateStatus(rawId, RawTradeIngestTransition.sourceKeyDuplicate());
		return TradeIngestItemOutcome.duplicate();
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

	private record TradeIdentity(String sourceKey, String payloadHash) {
	}

	private record MatchAttempt(ComplexMasterBootstrapResult bootstrapResult, ComplexMatchResult match) {

		private boolean matched() {
			return match != null && match.matched();
		}
	}
}
