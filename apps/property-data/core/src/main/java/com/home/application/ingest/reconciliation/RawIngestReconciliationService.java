package com.home.application.ingest.reconciliation;

import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.raw.RawTradeItemParser;
import com.home.application.ingest.trade.TradeIngestFinalizer;
import com.home.application.ingest.trade.TradeIngestItemOutcome;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.Objects;
import java.util.Optional;

public class RawIngestReconciliationService {

    private final RawTradeIngestRepository rawTradeIngestRepository;
    private final RawTradeItemParser rawTradeItemParser;
    private final TradeIngestFinalizer finalizer;

    public RawIngestReconciliationService(
            RawTradeIngestRepository rawTradeIngestRepository,
            RawTradeItemParser rawTradeItemParser,
            TradeIngestFinalizer finalizer) {
        this.rawTradeIngestRepository = Objects.requireNonNull(rawTradeIngestRepository);
        this.rawTradeItemParser = Objects.requireNonNull(rawTradeItemParser);
        this.finalizer = Objects.requireNonNull(finalizer);
    }

    public RawIngestReconciliationResult reconcileReceived(int limit) {
        if (limit <= 0) {
            return RawIngestReconciliationResult.empty();
        }
        RawIngestReconciliationResult result = RawIngestReconciliationResult.empty();
        for (RawTradeIngestRecord raw : rawTradeIngestRepository.findByStatus(RawTradeIngestStatus.RECEIVED, limit)) {
            Optional<OpenApiTradeItem> item = rawTradeItemParser.parse(raw);
            if (item.isEmpty()) {
                continue;
            }
            TradeIngestItemOutcome outcome = finalizer.finalizeReceived(raw, item.get());
            result = result.plus(outcome);
        }
        return result;
    }
}
