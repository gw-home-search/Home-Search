package com.home.application.ingest.trade;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiTradeIngestCorrelationTest {

    @Test
    @DisplayName("Batch execution correlation은 ingest item processor까지 전달된다")
    void executionCorrelationIsForwardedToEveryItem() {
        TradeIngestItemProcessor processor = mock(TradeIngestItemProcessor.class);
        TradeIngestMetrics metrics = mock(TradeIngestMetrics.class);
        OpenApiTradeIngestService service = new OpenApiTradeIngestService(processor, metrics);
        OpenApiTradeItem item = mock(OpenApiTradeItem.class);
        OpenApiTradeIngestBatch batch = new OpenApiTradeIngestBatch("RTMS", "11680", "202607", 1, List.of(item));
        ExecutionCorrelationId executionId = ExecutionCorrelationId.from("123e4567-e89b-12d3-a456-426614174006");
        when(processor.process(batch, item, executionId)).thenReturn(TradeIngestItemOutcome.normalized());

        service.ingest(batch, executionId);

        verify(processor).process(batch, item, executionId);
    }
}
