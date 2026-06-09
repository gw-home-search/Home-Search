package com.home.application.ingest.trade;

import java.util.Objects;

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
		String sourceKey = sourceKeyGenerator.generate(batch.source(), item);
		String payloadHash = sourceKeyGenerator.hashPayload(item.payload());
		RawTradeIngestRecord raw = rawTradeIngestRepository.save(RawTradeIngestRecord.received(
			batch.source(),
			sourceKey,
			batch.lawdCd(),
			batch.dealYmd(),
			batch.pageNo(),
			item.payload(),
			payloadHash
		));

		if (rawTradeIngestRepository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
			raw.id(),
			batch.source(),
			sourceKey,
			payloadHash
		)) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.sourceKeyDuplicate());
			return TradeIngestItemOutcome.duplicate();
		}

		if (item.isCanceled()) {
			try {
				ParsedRtmsTrade.from(item);
			}
			catch (IllegalArgumentException exception) {
				rawTradeIngestRepository.updateStatus(
					raw.id(),
					RawTradeIngestTransition.parseFailed(exception.getMessage())
				);
				return TradeIngestItemOutcome.parseFailed();
			}
			normalizedTradeRepository.cancelBySourceAndSourceKey(batch.source(), sourceKey, raw.id());
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.canceledSourceKey());
			return TradeIngestItemOutcome.canceled();
		}

		if (normalizedTradeRepository.existsBySourceAndSourceKey(batch.source(), sourceKey)) {
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.sourceKeyDuplicate());
			return TradeIngestItemOutcome.duplicate();
		}

		ParsedRtmsTrade parsedTrade;
		try {
			parsedTrade = ParsedRtmsTrade.from(item);
		}
		catch (IllegalArgumentException exception) {
			rawTradeIngestRepository.updateStatus(
				raw.id(),
				RawTradeIngestTransition.parseFailed(exception.getMessage())
			);
			return TradeIngestItemOutcome.parseFailed();
		}

		ComplexMasterBootstrapResult bootstrapResult = complexMasterBootstrapper.bootstrap(item);
		ComplexMatchResult match = complexMatcher.match(item);
		tradeMatchEvidenceRepository.save(TradeMatchEvidenceCommand.from(raw.id(), batch.source(), item, match));
		if (match == null || !match.matched()) {
			rawTradeIngestRepository.updateStatus(
				raw.id(),
				RawTradeIngestTransition.matchFailed(matchFailureReason(match, bootstrapResult))
			);
			return TradeIngestItemOutcome.matchFailed();
		}

		NormalizedTradeCommand command = new NormalizedTradeCommand(
			raw.id(),
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
			rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.normalized());
			return TradeIngestItemOutcome.normalized();
		}
		rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.fallbackIdentityDuplicate());
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
}
