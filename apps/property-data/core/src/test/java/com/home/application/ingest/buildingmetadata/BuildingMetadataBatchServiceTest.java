package com.home.application.ingest.buildingmetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.buildingmetadata.BuildingMetadataValues;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

class BuildingMetadataBatchServiceTest {
	private static final String PNU = "1168010300101400001";
	private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	@Test
	@DisplayName("동일 PNU 복수 단지는 외부 원천 호출 없이 모호 상태를 기록한다")
	void recordsSharedPnuWithoutCallingExternalSource() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataTarget target = target(2, null);
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 1, null, null)).willReturn(List.of(target));
		given(repository.recordAmbiguousPnu(target, REQUEST_ID))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.AMBIGUOUS, false));

		BuildingMetadataBatchSummary summary = new BuildingMetadataBatchService(repository,client,response -> null)
			.collect("missing",1,null,null,REQUEST_ID);

		assertThat(summary.requests()).isZero();
		verify(client, never()).fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("단일동은 표제부를 우선하고 후보가 없을 때만 총괄표제부로 대체한다")
	void usesTitleForSingleBuildingAndFallsBackOnlyForZeroCandidates() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget target = target(1, 1);
		var titleResponse = response(BuildingMetadataSourceKind.BLD_TITLE);
		var recapResponse = response(BuildingMetadataSourceKind.BLD_RECAP_TITLE);
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing",2,null,null)).willReturn(List.of(target));
		given(client.fetch(BuildingMetadataSourceKind.BLD_TITLE,PNU)).willReturn(titleResponse);
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU)).willReturn(recapResponse);
		given(parser.parse(titleResponse)).willReturn(new ParsedBuildingMetadataSource(0,List.of()));
		given(parser.parse(recapResponse)).willReturn(new ParsedBuildingMetadataSource(1,List.of()));
		given(repository.recordFailure(org.mockito.ArgumentMatchers.eq(target),
			org.mockito.ArgumentMatchers.eq(BuildingMetadataSourceKind.BLD_RECAP_TITLE),
			org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(REQUEST_ID),org.mockito.ArgumentMatchers.any()))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.UNAVAILABLE,false));

		var summary = new BuildingMetadataBatchService(repository,client,parser).collect("missing",2,null,null,REQUEST_ID);

		assertThat(summary.requests()).isEqualTo(2);
		verify(client).fetch(BuildingMetadataSourceKind.BLD_TITLE,PNU);
		verify(client).fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU);
	}

	@Test
	@DisplayName("건축물대장 배치는 refresh mode를 거부한다")
	void rejectsRefreshMode() {
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		assertThatThrownBy(() -> new BuildingMetadataBatchService(mock(BuildingMetadataEvidenceRepository.class),client,r -> null)
			.collect("refresh",1,null,null,REQUEST_ID)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("단일 후보는 fallback 없이 projection에 전달한다")
	void appliesSingleCandidateWithoutFallback() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget target = target(1,8);
		var response = response(BuildingMetadataSourceKind.BLD_RECAP_TITLE);
		var parsed = new ParsedBuildingMetadataSource(1,List.of(new com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate(
			"BLD-1",PNU,List.of("Sample"),BuildingMetadataValues.empty(),null)));
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing",3,null,null)).willReturn(List.of(target));
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU)).willReturn(response);
		given(parser.parse(response)).willReturn(parsed);
		given(repository.apply(target,BuildingMetadataSourceKind.BLD_RECAP_TITLE,parsed,REQUEST_ID))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.RESOLVED,true));

		var summary = new BuildingMetadataBatchService(repository,client,parser).collect("missing",3,null,null,REQUEST_ID);
		assertThat(summary.resolved()).isOne();
		assertThat(summary.requests()).isOne();
		verify(client).fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU);
	}

	@Test
	@DisplayName("과다 후보와 원천 실패를 즉시 재시도 없이 attempt로 변환한다")
	void mapsOversizedHttpAndParserFailuresToAttemptsWithoutImmediateRetry() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget target = target(1,8);
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing",1,null,null)).willReturn(List.of(target));
		var response = response(BuildingMetadataSourceKind.BLD_RECAP_TITLE);
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU)).willReturn(response);
		given(parser.parse(response)).willReturn(new ParsedBuildingMetadataSource(101,List.of()));
		given(repository.recordFailure(org.mockito.ArgumentMatchers.eq(target),org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(REQUEST_ID),org.mockito.ArgumentMatchers.isNull()))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.AMBIGUOUS,false));
		assertThat(new BuildingMetadataBatchService(repository,client,parser).collect("missing",1,null,null,REQUEST_ID)
			.reviewRequired()).isOne();

		BuildingMetadataSourceClient broken = mock(BuildingMetadataSourceClient.class);
		given(broken.isConfigured()).willReturn(true);
		given(broken.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE,PNU)).willThrow(new IllegalStateException("network"));
		given(repository.recordFailure(org.mockito.ArgumentMatchers.eq(target),org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(ComplexMetadataStatus.FAILED),org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(REQUEST_ID),org.mockito.ArgumentMatchers.any()))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.FAILED,false));
		assertThat(new BuildingMetadataBatchService(repository,broken,parser).collect("missing",1,null,null,REQUEST_ID).failed()).isOne();
	}

	@Test
	@DisplayName("원천 미설정과 양수가 아닌 요청 한도를 거부한다")
	void rejectsUnconfiguredClientAndNonPositiveLimit() {
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		given(client.isConfigured()).willReturn(false);
		BuildingMetadataBatchService service = new BuildingMetadataBatchService(mock(BuildingMetadataEvidenceRepository.class),client,r -> null);
		assertThatThrownBy(() -> service.collect("missing",1,null,null,REQUEST_ID)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.collect("missing",0,null,null,REQUEST_ID)).isInstanceOf(IllegalArgumentException.class);
	}

	private BuildingMetadataTarget target(int count,Integer dongCnt) {
		return new BuildingMetadataTarget(501,PNU,count,null,new BuildingMetadataValues(dongCnt,740,null,null,null,null,null,null));
	}
	private BuildingMetadataSourceResponse response(BuildingMetadataSourceKind kind) {
		return new BuildingMetadataSourceResponse(kind,PNU,200,"00","{}");
	}
}
