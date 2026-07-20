package com.home.application.ingest.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterRawServicesTest {
    @Test
    @DisplayName("건축물대장 원문 저장 처리를 검증한다")
    void receiptServiceReturnsTheIndependentlyStoredRawPageId() {
        BuildingRegisterRawPageRepository repository = mock(BuildingRegisterRawPageRepository.class);
        BuildingRegisterRawPageReceiptCommand command = new BuildingRegisterRawPageReceiptCommand(
                1, UUID.fromString("123e4567-e89b-12d3-a456-426614174192"), 1, 1, "{}", "a".repeat(64), 2, 200, null);
        when(repository.receive(command)).thenReturn(42L);

        assertThat(new BuildingRegisterRawReceiptService(repository).receive(command))
                .isEqualTo(42L);
        verify(repository).receive(command);
    }

    @Test
    @DisplayName("건축물대장 원문 저장 처리를 검증한다")
    void finalizerStoresNormalizedRecordsBeforePublishingTotalCount() {
        BuildingRegisterRawPageRepository repository = mock(BuildingRegisterRawPageRepository.class);
        BuildingRegisterEndpointSnapshotStore snapshots = mock(BuildingRegisterEndpointSnapshotStore.class);
        BuildingRegisterRawPageFinalizer finalizer = new BuildingRegisterRawPageFinalizer(repository, snapshots);

        finalizer.complete(10, 20, 3, BuildingRegisterRawPageStatus.PARSED, List.of());

        verify(repository).complete(10, BuildingRegisterRawPageStatus.PARSED, List.of());
        verify(snapshots).observeTotalCount(20, 3);
    }

    @Test
    @DisplayName("건축물대장 원문 저장 처리를 검증한다")
    void finalizerDoesNotInventTotalCountForTransportOrParseFailures() {
        BuildingRegisterRawPageRepository repository = mock(BuildingRegisterRawPageRepository.class);
        BuildingRegisterEndpointSnapshotStore snapshots = mock(BuildingRegisterEndpointSnapshotStore.class);

        new BuildingRegisterRawPageFinalizer(repository, snapshots)
                .complete(10, 20, null, BuildingRegisterRawPageStatus.PARSE_FAILED, List.of());

        verify(repository).complete(10, BuildingRegisterRawPageStatus.PARSE_FAILED, List.of());
        verifyNoInteractions(snapshots);
    }
}
