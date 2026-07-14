package com.home.application.ingest.trade;

import com.home.application.ingest.raw.RawReceiptService;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import com.home.ingestcore.rtms.SourceKeyGenerator;
import java.util.Objects;

/**
 * Open API trade item 하나의 raw receipt와 transactional finalization 경계를 연결합니다.
 */
public class TradeIngestItemProcessor {

    private final RawReceiptService rawReceiptService;
    private final TradeIngestFinalizer finalizer;
    private final SourceKeyGenerator sourceKeyGenerator;

    public TradeIngestItemProcessor(RawReceiptService rawReceiptService, TradeIngestFinalizer finalizer) {
        this(rawReceiptService, finalizer, new SourceKeyGenerator());
    }

    TradeIngestItemProcessor(
            RawReceiptService rawReceiptService,
            TradeIngestFinalizer finalizer,
            SourceKeyGenerator sourceKeyGenerator) {
        this.rawReceiptService = Objects.requireNonNull(rawReceiptService);
        this.finalizer = Objects.requireNonNull(finalizer);
        this.sourceKeyGenerator = Objects.requireNonNull(sourceKeyGenerator);
    }

    public TradeIngestItemOutcome process(OpenApiTradeIngestBatch batch, OpenApiTradeItem item) {
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
                sourceKeyGenerator.hashPayload(item.payload())));
        return finalizer.finalizeReceived(raw, item);
    }
}
