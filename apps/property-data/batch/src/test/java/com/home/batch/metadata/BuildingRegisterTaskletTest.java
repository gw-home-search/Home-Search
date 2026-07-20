package com.home.batch.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.home.application.ingest.buildingregister.BuildingRatioProjectCommand;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionService;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class BuildingRegisterTaskletTest {
    @Test
    void collectionTaskletEnforcesQuotaAndUsesSharedMetadataLock() throws Exception {
        BuildingRegisterCampaignService service = mock(BuildingRegisterCampaignService.class);
        BuildingMetadataExecutionLock lock = mock(BuildingMetadataExecutionLock.class);
        BuildingMetadataExecutionLock.Lock acquired = mock(BuildingMetadataExecutionLock.Lock.class);
        given(lock.acquire()).willReturn(acquired);
        var tasklet = new BuildingRegisterCollectTasklet(service, lock, 1000);

        tasklet.execute(
                null,
                context(Map.of(
                        "collectionId", "123e4567-e89b-12d3-a456-426614174190",
                        "requestId", "123e4567-e89b-12d3-a456-426614174191",
                        "runDate", "2026-07-20",
                        "mode", "missing",
                        "strategy", "adaptive",
                        "maxRequests", "900",
                        "toComplexId", "1000")));

        ArgumentCaptor<BuildingRegisterCampaignCommand> command =
                ArgumentCaptor.forClass(BuildingRegisterCampaignCommand.class);
        verify(service).collect(command.capture());
        assertThat(command.getValue().maxRequests()).isEqualTo(900);
        verify(acquired).close();
        assertThatThrownBy(() -> new BuildingRegisterCollectTasklet(service, lock, 1000)
                        .execute(
                                null,
                                context(Map.of(
                                        "collectionId", "123e4567-e89b-12d3-a456-426614174190",
                                        "requestId", "123e4567-e89b-12d3-a456-426614174191",
                                        "runDate", "2026-07-20",
                                        "mode", "missing",
                                        "strategy", "adaptive",
                                        "maxRequests", "901",
                                        "toComplexId", "1000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("90%");
    }

    @Test
    void projectionTaskletUsesSameMetadataLock() throws Exception {
        BuildingRatioProjectionService service = mock(BuildingRatioProjectionService.class);
        BuildingMetadataExecutionLock lock = mock(BuildingMetadataExecutionLock.class);
        BuildingMetadataExecutionLock.Lock acquired = mock(BuildingMetadataExecutionLock.Lock.class);
        given(lock.acquire()).willReturn(acquired);

        new BuildingRatioProjectTasklet(service, lock)
                .execute(
                        null,
                        context(Map.of(
                                "collectionId", "123e4567-e89b-12d3-a456-426614174190",
                                "requestId", "123e4567-e89b-12d3-a456-426614174192",
                                "maxTargets", "100")));

        verify(service).project(org.mockito.ArgumentMatchers.any(BuildingRatioProjectCommand.class));
        verify(acquired).close();
    }

    private ChunkContext context(Map<String, Object> params) {
        ChunkContext context = mock(ChunkContext.class);
        StepContext step = mock(StepContext.class);
        given(context.getStepContext()).willReturn(step);
        given(step.getJobParameters()).willReturn(params);
        return context;
    }
}
