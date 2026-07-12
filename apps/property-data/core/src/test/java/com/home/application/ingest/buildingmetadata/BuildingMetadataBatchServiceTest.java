package com.home.application.ingest.buildingmetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

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
		given(repository.findTargets("missing", 1, null, null,REQUEST_ID)).willReturn(List.of(target));
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
		given(repository.findTargets("missing",2,null,null,REQUEST_ID)).willReturn(List.of(target));
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
		given(repository.findTargets("missing",3,null,null,REQUEST_ID)).willReturn(List.of(target));
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
		given(repository.findTargets("missing",1,null,null,REQUEST_ID)).willReturn(List.of(target));
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

	@Test
	@DisplayName("fallback이 quota를 소진하면 다음 대상에 maxRequests+1 호출을 하지 않는다")
	void neverCallsBeyondMaxRequestsAfterFallback() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget first = target(1, 1);
		BuildingMetadataTarget second = new BuildingMetadataTarget(502, "1168010300101400002", 1, null,
			new BuildingMetadataValues(1, 100, null, null, null, null, null, null));
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 2, null, null,REQUEST_ID)).willReturn(List.of(first, second));
		given(client.fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
			.willAnswer(invocation -> new BuildingMetadataSourceResponse(invocation.getArgument(0), invocation.getArgument(1), 200, "00", "{}"));
		given(parser.parse(org.mockito.ArgumentMatchers.any())).willReturn(new ParsedBuildingMetadataSource(0, List.of()));
		given(repository.recordFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(REQUEST_ID), org.mockito.ArgumentMatchers.any()))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.UNAVAILABLE, false));

		var summary = new BuildingMetadataBatchService(repository, client, parser)
			.collect("missing", 2, null, null, REQUEST_ID);

		assertThat(summary.requests()).isEqualTo(2);
		verify(client, times(2)).fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("2 MiB 초과 응답은 projection 없이 PERMANENT attempt로 기록한다")
	void oversizedPayloadIsPermanentWithoutParsingOrFallback() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget target = target(1, 8);
		var oversized = new BuildingMetadataSourceResponse(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU, 200,
			"00", null, 2_097_153L, "hash", true);
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 1, null, null,REQUEST_ID)).willReturn(List.of(target));
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU)).willReturn(oversized);
		given(repository.recordFailure(target, BuildingMetadataSourceKind.BLD_RECAP_TITLE, ComplexMetadataStatus.FAILED,
			com.home.domain.complex.metadata.ComplexMetadataFailureKind.PERMANENT, "building source payload exceeds 2 MiB",
			REQUEST_ID, null)).willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.FAILED, false));

		new BuildingMetadataBatchService(repository, client, parser).collect("missing", 1, null, null, REQUEST_ID);

		verify(parser, never()).parse(org.mockito.ArgumentMatchers.any());
		verify(repository).recordFailure(target, BuildingMetadataSourceKind.BLD_RECAP_TITLE, ComplexMetadataStatus.FAILED,
			com.home.domain.complex.metadata.ComplexMetadataFailureKind.PERMANENT, "building source payload exceeds 2 MiB",
			REQUEST_ID, null);
	}

	@Test
	@DisplayName("dong_cnt가 NULL이면 총괄표제부를 primary source로 선택한다")
	void nullBuildingCountUsesRecapTitle() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataSourceParser parser = mock(BuildingMetadataSourceParser.class);
		BuildingMetadataTarget target = target(1, null);
		var response = response(BuildingMetadataSourceKind.BLD_RECAP_TITLE);
		var parsed = new ParsedBuildingMetadataSource(1, List.of(
			new com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate(
				"BLD-1", PNU, List.of("Sample"), BuildingMetadataValues.empty(), null)));
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 1, null, null, REQUEST_ID)).willReturn(List.of(target));
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU)).willReturn(response);
		given(parser.parse(response)).willReturn(parsed);
		given(repository.apply(target, BuildingMetadataSourceKind.BLD_RECAP_TITLE, parsed, REQUEST_ID))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.PARTIAL, true));

		new BuildingMetadataBatchService(repository, client, parser).collect("missing", 1, null, null, REQUEST_ID);

		verify(client).fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU);
		verify(client, never()).fetch(org.mockito.ArgumentMatchers.eq(BuildingMetadataSourceKind.BLD_TITLE),
			org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("인증·quota HTTP 오류는 attempt 기록 후 job 전체를 즉시 중단한다")
	void authenticationOrQuotaFailureStopsJob() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataTarget target = target(1, 8);
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 2, null, null, REQUEST_ID)).willReturn(List.of(target));
		given(client.fetch(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU))
			.willReturn(new BuildingMetadataSourceResponse(BuildingMetadataSourceKind.BLD_RECAP_TITLE, PNU, 429, null, "{}"));

		assertThatThrownBy(() -> new BuildingMetadataBatchService(repository, client, response -> null)
			.collect("missing", 2, null, null, REQUEST_ID))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("quota");
		verify(repository).recordFailure(target, BuildingMetadataSourceKind.BLD_RECAP_TITLE,
			ComplexMetadataStatus.FAILED, com.home.domain.complex.metadata.ComplexMetadataFailureKind.PERMANENT,
			"building source authentication or quota failure", REQUEST_ID, null);
	}

	@Test
	@DisplayName("연속 transient provider 실패 3회는 job 전체를 중단한다")
	void threeConsecutiveTransientFailuresStopJob() {
		BuildingMetadataEvidenceRepository repository = mock(BuildingMetadataEvidenceRepository.class);
		BuildingMetadataSourceClient client = mock(BuildingMetadataSourceClient.class);
		BuildingMetadataTarget one = target(1, 8);
		BuildingMetadataTarget two = new BuildingMetadataTarget(502, "1168010300101400002", 1, null,
			one.currentValues());
		BuildingMetadataTarget three = new BuildingMetadataTarget(503, "1168010300101400003", 1, null,
			one.currentValues());
		given(client.isConfigured()).willReturn(true);
		given(repository.findTargets("missing", 3, null, null, REQUEST_ID)).willReturn(List.of(one, two, three));
		given(client.fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
			.willThrow(new IllegalStateException("temporary network failure"));
		given(repository.recordFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.eq(ComplexMetadataStatus.FAILED),
			org.mockito.ArgumentMatchers.eq(com.home.domain.complex.metadata.ComplexMetadataFailureKind.TRANSIENT),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(REQUEST_ID),
			org.mockito.ArgumentMatchers.any()))
			.willReturn(new BuildingMetadataAttemptResult(ComplexMetadataStatus.FAILED, false));

		assertThatThrownBy(() -> new BuildingMetadataBatchService(repository, client, response -> null)
			.collect("missing", 3, null, null, REQUEST_ID))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("3 consecutive");
		verify(client, times(3)).fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
	}

	private BuildingMetadataTarget target(int count,Integer dongCnt) {
		return new BuildingMetadataTarget(501,PNU,count,null,new BuildingMetadataValues(dongCnt,740,null,null,null,null,null,null));
	}
	private BuildingMetadataSourceResponse response(BuildingMetadataSourceKind kind) {
		return new BuildingMetadataSourceResponse(kind,PNU,200,"00","{}");
	}
}
