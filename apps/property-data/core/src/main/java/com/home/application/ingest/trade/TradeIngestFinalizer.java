package com.home.application.ingest.trade;

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
import com.home.ingestcore.rtms.OpenApiTradeItem;
import com.home.ingestcore.rtms.ParsedRtmsTrade;
import com.home.ingestcore.rtms.TradeExclAreaNormalizer;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class TradeIngestFinalizer {

    private final RawTradeIngestRepository rawTradeIngestRepository;
    private final NormalizedTradeRepository normalizedTradeRepository;
    private final ComplexMatcher complexMatcher;
    private final ComplexMasterBootstrapper complexMasterBootstrapper;
    private final TradeMatchEvidenceRepository tradeMatchEvidenceRepository;

    public TradeIngestFinalizer(
            RawTradeIngestRepository rawTradeIngestRepository,
            NormalizedTradeRepository normalizedTradeRepository,
            ComplexMatcher complexMatcher,
            ComplexMasterBootstrapper complexMasterBootstrapper,
            TradeMatchEvidenceRepository tradeMatchEvidenceRepository) {
        this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
        this.normalizedTradeRepository = Objects.requireNonNull(normalizedTradeRepository);
        this.complexMatcher = Objects.requireNonNull(complexMatcher);
        this.complexMasterBootstrapper = Objects.requireNonNull(complexMasterBootstrapper);
        this.tradeMatchEvidenceRepository = Objects.requireNonNull(tradeMatchEvidenceRepository);
    }

    @Transactional
    public TradeIngestItemOutcome finalizeReceived(RawTradeIngestRecord raw, OpenApiTradeItem item) {
        Objects.requireNonNull(raw, "raw is required");
        Objects.requireNonNull(item, "item is required");

        if (hasProcessedDuplicate(raw)) {
            return sourceKeyDuplicate(raw.id());
        }
        if (item.isCanceled()) {
            return cancelTrade(item, raw);
        }
        if (normalizedTradeRepository.existsBySourceAndSourceKey(raw.source(), raw.sourceKey())) {
            return sourceKeyDuplicate(raw.id());
        }

        Optional<ParsedRtmsTrade> parsedTrade = parseOrMarkFailed(raw.id(), item);
        if (parsedTrade.isEmpty()) {
            return TradeIngestItemOutcome.parseFailed();
        }

        MatchAttempt matchAttempt = matchAndRecordEvidence(raw.id(), raw.source(), item);
        if (!matchAttempt.matched()) {
            return matchFailed(raw.id(), matchAttempt);
        }
        return normalizeTrade(raw, item, parsedTrade.get(), matchAttempt.match());
    }

    private boolean hasProcessedDuplicate(RawTradeIngestRecord raw) {
        return rawTradeIngestRepository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
                raw.id(), raw.source(), raw.sourceKey(), raw.payloadHash());
    }

    private TradeIngestItemOutcome cancelTrade(OpenApiTradeItem item, RawTradeIngestRecord raw) {
        if (parseOrMarkFailed(raw.id(), item).isEmpty()) {
            return TradeIngestItemOutcome.parseFailed();
        }
        normalizedTradeRepository.cancelBySourceAndSourceKey(raw.source(), raw.sourceKey(), raw.id());
        rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.canceledSourceKey());
        return TradeIngestItemOutcome.canceled();
    }

    private Optional<ParsedRtmsTrade> parseOrMarkFailed(long rawId, OpenApiTradeItem item) {
        try {
            return Optional.of(ParsedRtmsTrade.from(item));
        } catch (IllegalArgumentException exception) {
            rawTradeIngestRepository.updateStatus(rawId, RawTradeIngestTransition.parseFailed(exception.getMessage()));
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
                        matchFailureReason(matchAttempt.match(), matchAttempt.bootstrapResult())));
        return TradeIngestItemOutcome.matchFailed();
    }

    private TradeIngestItemOutcome normalizeTrade(
            RawTradeIngestRecord raw, OpenApiTradeItem item, ParsedRtmsTrade parsedTrade, ComplexMatchResult match) {
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
                item.aptSeq());

        if (normalizedTradeRepository.insertIfAbsent(command)) {
            rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.normalized());
            return TradeIngestItemOutcome.normalized();
        }
        rawTradeIngestRepository.updateStatus(raw.id(), RawTradeIngestTransition.fallbackIdentityDuplicate());
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

    private record MatchAttempt(ComplexMasterBootstrapResult bootstrapResult, ComplexMatchResult match) {

        private boolean matched() {
            return match != null && match.matched();
        }
    }
}
