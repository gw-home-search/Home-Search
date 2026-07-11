package com.home.batch.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import com.home.application.ingest.buildingmetadata.BuildingMetadataBatchService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class BuildingMetadataTaskletTest {
	@Test
	@DisplayName("collection tasklet은 90% quota 안에서 bounded 인자를 use case에 전달하고 lock을 해제한다")
	void executesCollectionWithinQuota() throws Exception {
		BuildingMetadataBatchService service = mock(BuildingMetadataBatchService.class);
		BuildingMetadataExecutionLock lock = mock(BuildingMetadataExecutionLock.class);
		BuildingMetadataExecutionLock.Lock acquired = mock(BuildingMetadataExecutionLock.Lock.class);
		given(lock.acquire()).willReturn(acquired);
		BuildingMetadataCollectTasklet tasklet = new BuildingMetadataCollectTasklet(service,lock,1000);
		ChunkContext context = context(Map.of("mode","missing","maxRequests","900","fromComplexId","100",
			"toComplexId","200","requestId","123e4567-e89b-12d3-a456-426614174000"));

		tasklet.execute(null,context);

		verify(service).collect("missing",900,100L,200L,UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
		verify(acquired).close();
	}

	@Test
	@DisplayName("collection tasklet은 승인 quota 90%를 넘는 요청을 외부 호출 전에 거부한다")
	void rejectsCollectionOverQuota() {
		BuildingMetadataCollectTasklet tasklet = new BuildingMetadataCollectTasklet(mock(BuildingMetadataBatchService.class),
			mock(BuildingMetadataExecutionLock.class),1000);

		assertThatThrownBy(() -> tasklet.execute(null,context(Map.of("mode","missing","maxRequests","901",
			"requestId","123e4567-e89b-12d3-a456-426614174000"))))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("90%");
	}

	private ChunkContext context(Map<String,Object> params) {
		ChunkContext context = mock(ChunkContext.class); StepContext step = mock(StepContext.class);
		given(context.getStepContext()).willReturn(step); given(step.getJobParameters()).willReturn(params); return context;
	}
}
