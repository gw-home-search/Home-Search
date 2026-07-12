package com.home.batch.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import com.home.application.ingest.metadata.OdcMetadataGapFillService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class OdcMetadataGapFillTaskletTest {
	@Test
	@DisplayName("ODC gap-fill은 canonical과 alias 최대 2회 호출을 반영한 90% quota 안에서 실행한다")
	void enforcesWorstCaseOdcQuota() throws Exception {
		OdcMetadataGapFillService service = mock(OdcMetadataGapFillService.class);
		BuildingMetadataExecutionLock lock = mock(BuildingMetadataExecutionLock.class);
		BuildingMetadataExecutionLock.Lock acquired = mock(BuildingMetadataExecutionLock.Lock.class);
		given(lock.acquire()).willReturn(acquired);
		OdcMetadataGapFillTasklet tasklet = new OdcMetadataGapFillTasklet(service, lock, 1000);

		tasklet.execute(null, context(Map.of("maxTargets", "450", "toComplexId", "1000",
			"requestId", "123e4567-e89b-12d3-a456-426614174005")));

		verify(service).fill(450, null, 1000L, UUID.fromString("123e4567-e89b-12d3-a456-426614174005"));
		verify(acquired).close();
		assertThatThrownBy(() -> tasklet.execute(null, context(Map.of("maxTargets", "451", "toComplexId", "1000",
			"requestId", "123e4567-e89b-12d3-a456-426614174006"))))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTargets x 2");
	}

	private ChunkContext context(Map<String,Object> params) {
		ChunkContext context = mock(ChunkContext.class);
		StepContext step = mock(StepContext.class);
		given(context.getStepContext()).willReturn(step);
		given(step.getJobParameters()).willReturn(params);
		return context;
	}
}
