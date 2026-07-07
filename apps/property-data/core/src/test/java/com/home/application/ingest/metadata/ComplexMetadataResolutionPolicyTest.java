package com.home.application.ingest.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataResolutionPolicyTest {

	private final ComplexMetadataResolutionPolicy policy = new ComplexMetadataResolutionPolicy();

	@Test
	@DisplayName("ODC metadata와 building metadata가 모두 있으면 충돌 없는 값을 병합한다")
	void mergesOdcloudAndBuildingMetadata() {
		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.partial("ODC", new ComplexMetadata(
				8, 740, null, null, null, null, null, LocalDate.of(2015, 3, 20)
			)),
			() -> ComplexMetadataResolution.partial("BLD", new ComplexMetadata(
				null,
				740,
				new BigDecimal("12345.67"),
				new BigDecimal("2345.67"),
				new BigDecimal("98765.43"),
				new BigDecimal("22.5"),
				new BigDecimal("199.8"),
				null
			))
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.RESOLVED);
		assertThat(result.source()).isEqualTo("ODC+BLD");
		assertThat(result.metadata().dongCnt()).isEqualTo(8);
		assertThat(result.metadata().unitCnt()).isEqualTo(740);
		assertThat(result.metadata().platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
		assertThat(result.metadata().useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
	}

	@Test
	@DisplayName("ODC와 building metadata가 같은 필드에서 충돌하면 AMBIGUOUS로 보류한다")
	void returnsAmbiguousWhenSourcesConflict() {
		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.partial("ODC", new ComplexMetadata(
				8, 740, null, null, null, null, null, LocalDate.of(2015, 3, 20)
			)),
			() -> ComplexMetadataResolution.partial("BLD", new ComplexMetadata(
				null, 741, null, null, null, null, null, null
			))
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(result.source()).isEqualTo("ODC+BLD");
		assertThat(result.failureKind()).isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
		assertThat(result.failureReason()).contains("complex metadata source conflict");
	}

	@Test
	@DisplayName("ODC가 ambiguous이면 building fallback으로 덮지 않는다")
	void preservesAmbiguousOdcloudWithoutCallingBuilding() {
		AtomicBoolean buildingCalled = new AtomicBoolean(false);

		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous"),
			() -> {
				buildingCalled.set(true);
				return ComplexMetadataResolution.partial("BLD", ComplexMetadata.empty());
			}
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.AMBIGUOUS);
		assertThat(result.source()).isEqualTo("ODC");
		assertThat(buildingCalled).isFalse();
	}

	@Test
	@DisplayName("ODC가 failed이면 building fallback으로 덮지 않는다")
	void preservesFailedOdcloudWithoutCallingBuilding() {
		AtomicBoolean buildingCalled = new AtomicBoolean(false);

		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.failed("ODC", ComplexMetadataFailureKind.TRANSIENT, "timeout"),
			() -> {
				buildingCalled.set(true);
				return ComplexMetadataResolution.partial("BLD", ComplexMetadata.empty());
			}
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.FAILED);
		assertThat(result.source()).isEqualTo("ODC");
		assertThat(buildingCalled).isFalse();
	}

	@Test
	@DisplayName("building fallback이 꺼져 있으면 ODC unavailable을 그대로 반환한다")
	void preservesOdcloudUnavailableWhenFallbackDisabled() {
		AtomicBoolean buildingCalled = new AtomicBoolean(false);

		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			false,
			ComplexMetadataResolution.unavailable("ODC", "ODC candidate unavailable"),
			() -> {
				buildingCalled.set(true);
				return ComplexMetadataResolution.partial("BLD", ComplexMetadata.empty());
			}
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.UNAVAILABLE);
		assertThat(result.source()).isEqualTo("ODC");
		assertThat(buildingCalled).isFalse();
	}

	@Test
	@DisplayName("ODC metadata가 있고 building 후보가 ambiguous이면 ODC metadata를 보존한다")
	void preservesOdcloudMetadataWhenBuildingIsAmbiguous() {
		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.partial("ODC", new ComplexMetadata(
				8, 740, null, null, null, null, null, LocalDate.of(2015, 3, 20)
			)),
			() -> ComplexMetadataResolution.ambiguous("BLD", "building apartment candidate ambiguous")
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.PARTIAL);
		assertThat(result.source()).isEqualTo("ODC");
		assertThat(result.metadata().dongCnt()).isEqualTo(8);
	}

	@Test
	@DisplayName("ODC metadata가 없고 fallback이 켜져 있으면 building metadata를 반환한다")
	void usesBuildingFallbackWhenOdcloudHasNoMetadata() {
		ComplexMetadataResolution result = policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.unavailable("ODC", "ODC candidate unavailable"),
			() -> ComplexMetadataResolution.partial("BLD", new ComplexMetadata(
				null, 740, null, null, null, null, null, null
			))
		);

		assertThat(result.status()).isEqualTo(ComplexMetadataStatus.PARTIAL);
		assertThat(result.source()).isEqualTo("BLD");
		assertThat(result.metadata().unitCnt()).isEqualTo(740);
	}

	@Test
	@DisplayName("fallback이 필요한데 building supplier가 없으면 실패한다")
	void requiresBuildingSupplierWhenFallbackIsNeeded() {
		assertThatThrownBy(() -> policy.resolve(
			"1168010300107770001",
			true,
			ComplexMetadataResolution.unavailable("ODC", "ODC candidate unavailable"),
			null
		)).isInstanceOf(NullPointerException.class);
	}
}
