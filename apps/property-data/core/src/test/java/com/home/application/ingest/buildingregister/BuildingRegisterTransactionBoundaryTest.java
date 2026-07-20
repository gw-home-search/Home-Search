package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class BuildingRegisterTransactionBoundaryTest {
    @Test
    void separatesRequiresNewRawReceiptFromRequiredFinalization() throws Exception {
        Transactional receipt = BuildingRegisterRawReceiptService.class
                .getMethod("receive", BuildingRegisterRawPageReceiptCommand.class)
                .getAnnotation(Transactional.class);
        Transactional finalizer = BuildingRegisterRawPageFinalizer.class
                .getMethod(
                        "complete",
                        long.class,
                        long.class,
                        Integer.class,
                        BuildingRegisterRawPageStatus.class,
                        List.class)
                .getAnnotation(Transactional.class);

        assertThat(receipt.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(finalizer.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
