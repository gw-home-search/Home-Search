package com.home.application.ingest.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.coordinate.caseflow.CoordinateResolutionCommitCommand;
import com.home.application.coordinate.caseflow.CoordinateResolutionCommitter;
import com.home.application.ingest.raw.RawReceiptService;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.ingestcore.rtms.OpenApiTradeItem;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AtomicWorkflowTransactionBoundaryTest {

    @Test
    @DisplayName("raw receipt는 REQUIRES_NEW이고 coordinate/ingest finalizer는 독립 REQUIRED transaction 경계다")
    void declaresExpectedTransactionBoundaries() throws Exception {
        var receipt = RawReceiptService.class
                .getMethod("receive", RawTradeIngestRecord.class)
                .getAnnotation(Transactional.class);
        var ingestFinalizer = TradeIngestFinalizer.class
                .getMethod("finalizeReceived", RawTradeIngestRecord.class, OpenApiTradeItem.class)
                .getAnnotation(Transactional.class);
        var coordinateCommitter = CoordinateResolutionCommitter.class
                .getMethod("commit", CoordinateResolutionCommitCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(receipt.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(ingestFinalizer.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(coordinateCommitter.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(Modifier.isFinal(RawReceiptService.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(TradeIngestFinalizer.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(CoordinateResolutionCommitter.class.getModifiers()))
                .isFalse();
    }
}
