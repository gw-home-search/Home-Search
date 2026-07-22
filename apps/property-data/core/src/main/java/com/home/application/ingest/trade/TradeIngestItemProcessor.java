package com.home.application.ingest.trade;

import com.home.application.ingest.raw.RawReceiptService;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import com.home.ingestcore.rtms.SourceKeyGenerator;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Open API trade item 하나의 raw receipt와 transactional finalization 경계를 연결합니다.
 */
@Service
public class TradeIngestItemProcessor {

    private final RawReceiptService rawReceiptService;
    private final TradeIngestFinalizer finalizer;
    private final SourceKeyGenerator sourceKeyGenerator;

    public TradeIngestItemProcessor(RawReceiptService rawReceiptService, TradeIngestFinalizer finalizer) {
        this.rawReceiptService = Objects.requireNonNull(rawReceiptService);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.sourceKeyGenerator = new SourceKeyGenerator();
    }

    public TradeIngestItemOutcome process(OpenApiTradeIngestBatch batch, OpenApiTradeItem item) {
        return process(batch, item, null);
    }

    public TradeIngestItemOutcome process(
            OpenApiTradeIngestBatch batch, OpenApiTradeItem item, ExecutionCorrelationId executionCorrelationId) {
        Objects.requireNonNull(batch, "batch is required");
        Objects.requireNonNull(item, "item is required");
        String sourceKey = sourceKeyGenerator.generate(batch.source(), item);
        RawTradeIngestRecord raw = rawReceiptService.receive(RawTradeIngestRecord.received(
                batch.source(),
                sourceKey,
                batch.lawdCd(),
                batch.dealYmd(),
                batch.pageNo(),
                item.payload(),
                sourceKeyGenerator.hashPayload(item.payload()),
                executionCorrelationId));
        return finalizer.finalizeReceived(raw, item);
    }
}
