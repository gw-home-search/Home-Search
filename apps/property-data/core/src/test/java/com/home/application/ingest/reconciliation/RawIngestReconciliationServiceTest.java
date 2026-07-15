package com.home.application.ingest.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.application.ingest.raw.RawTradeItemParser;
import com.home.application.ingest.trade.TradeIngestFinalizer;
import com.home.application.ingest.trade.TradeIngestItemOutcome;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RawIngestReconciliationServiceTest {

    @Test
    @DisplayName("raw reconciliation service는 limit이 0 이하면 조회와 finalization을 하지 않는다")
    void returnsEmptyWhenLimitIsNotPositive() {
        RawTradeIngestRepository rawRepository = mock(RawTradeIngestRepository.class);
        RawTradeItemParser parser = mock(RawTradeItemParser.class);
        TradeIngestFinalizer finalizer = mock(TradeIngestFinalizer.class);
        RawIngestReconciliationService service = new RawIngestReconciliationService(rawRepository, parser, finalizer);

        assertThat(service.reconcileReceived(0)).isEqualTo(RawIngestReconciliationResult.empty());
        verifyNoInteractions(rawRepository, parser, finalizer);
    }

    @Test
    @DisplayName("raw reconciliation service는 recoverable RECEIVED 전체를 동일 finalizer로 재처리한다")
    void reprocessesAllRecoverableReceivedRowsThroughFinalizer() {
        RawTradeIngestRecord first = raw(101L, "source-101");
        RawTradeIngestRecord second = raw(102L, "source-102");
        OpenApiTradeItem item = item();
        RawTradeIngestRepository rawRepository = mock(RawTradeIngestRepository.class);
        RawTradeItemParser parser = mock(RawTradeItemParser.class);
        TradeIngestFinalizer finalizer = mock(TradeIngestFinalizer.class);
        when(rawRepository.findByStatus(RawTradeIngestStatus.RECEIVED, 5)).thenReturn(List.of(first, second));
        when(parser.parse(first)).thenReturn(Optional.of(item));
        when(parser.parse(second)).thenReturn(Optional.of(item));
        when(finalizer.finalizeReceived(first, item)).thenReturn(TradeIngestItemOutcome.normalized());
        when(finalizer.finalizeReceived(second, item)).thenReturn(TradeIngestItemOutcome.duplicate());
        RawIngestReconciliationService service = new RawIngestReconciliationService(rawRepository, parser, finalizer);

        RawIngestReconciliationResult result = service.reconcileReceived(5);

        assertThat(result).isEqualTo(new RawIngestReconciliationResult(2, 1));
        verify(finalizer).finalizeReceived(first, item);
        verify(finalizer).finalizeReceived(second, item);
    }

    private static RawTradeIngestRecord raw(Long id, String sourceKey) {
        return new RawTradeIngestRecord(
                id,
                "RTMS",
                sourceKey,
                "11680",
                "202512",
                1,
                "{}",
                "hash-" + sourceKey,
                RawTradeIngestStatus.RECEIVED,
                null,
                null,
                null);
    }

    private static OpenApiTradeItem item() {
        return new OpenApiTradeItem(
                "101",
                "Sample Apartment",
                "APT-501",
                "125,000",
                1,
                12,
                2025,
                84.93,
                12,
                "140-1",
                "11680",
                "10300",
                "{}");
    }
}
