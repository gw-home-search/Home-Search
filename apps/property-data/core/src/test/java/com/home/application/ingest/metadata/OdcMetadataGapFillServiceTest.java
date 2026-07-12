package com.home.application.ingest.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OdcMetadataGapFillServiceTest {
	private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174020");
	private static final ComplexMetadataLookup LOOKUP = new ComplexMetadataLookup(
		501L, "APT-501", "Sample", "1168010300101400001", "Sample address", 0);

	@Test
	@DisplayName("공유 PNU 대상은 ODC 호출 없이 AMBIGUOUS attempt를 기록한다")
	void sharedPnuIsRecordedWithoutExternalCall() {
		OdcMetadataGapFillRepository repository = mock(OdcMetadataGapFillRepository.class);
		OdcComplexMetadataResolver resolver = mock(OdcComplexMetadataResolver.class);
		OdcMetadataGapFillTarget target = new OdcMetadataGapFillTarget(LOOKUP, 2);
		given(resolver.isOdcConfigured()).willReturn(true);
		given(repository.findTargets(20, null, 1000L, REQUEST_ID)).willReturn(List.of(target));
		given(repository.recordAmbiguous(target, REQUEST_ID)).willReturn(OdcMetadataGapFillOutcome.ambiguous());

		OdcMetadataGapFillSummary summary = new OdcMetadataGapFillService(repository, resolver)
			.fill(20, null, 1000L, REQUEST_ID);

		assertThat(summary.ambiguous()).isOne();
		assertThat(summary.requests()).isZero();
		verify(resolver, never()).resolveOdc(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("단일 PNU 대상은 building fallback 없는 ODC resolver 결과를 저장한다")
	void resolvesSinglePnuThroughOdcOnly() {
		OdcMetadataGapFillRepository repository = mock(OdcMetadataGapFillRepository.class);
		OdcComplexMetadataResolver resolver = mock(OdcComplexMetadataResolver.class);
		OdcMetadataGapFillTarget target = new OdcMetadataGapFillTarget(LOOKUP, 1);
		ComplexMetadataResolution resolution = ComplexMetadataResolution.partial("ODC",
			new ComplexMetadata(8, null, null, null, null, null, null, null));
		given(resolver.isOdcConfigured()).willReturn(true);
		given(repository.findTargets(20, 500L, 1000L, REQUEST_ID)).willReturn(List.of(target));
		given(resolver.resolveOdc(LOOKUP)).willReturn(resolution);
		given(repository.saveResolution(target, resolution, REQUEST_ID)).willReturn(OdcMetadataGapFillOutcome.applied());

		OdcMetadataGapFillSummary summary = new OdcMetadataGapFillService(repository, resolver)
			.fill(20, 500L, 1000L, REQUEST_ID);

		assertThat(summary.requests()).isOne();
		assertThat(summary.applied()).isOne();
		verify(resolver).resolveOdc(LOOKUP);
	}
}
