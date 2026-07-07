package com.home.application.coordinate.footprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateGeometryPolicyTest {

	private final CoordinateGeometryPolicy policy = new CoordinateGeometryPolicy();

	@Test
	@DisplayName("동일 PNU feature bbox는 union bbox center로 parcel 좌표를 계산한다")
	void resolvesParcelCenterFromUnionBbox() {
		var center = policy.parcelCenter(
			"1168010300107770001",
			List.of(
				feature("1168010300107770001", "127.0", "37.0", "127.2", "37.2"),
				feature("1168010300107770001", "127.2", "37.2", "127.4", "37.4"),
				feature("9999999999999999999", "126.0", "36.0", "126.2", "36.2")
			)
		);

		assertThat(center).isPresent();
		assertThat(center.get().latitude()).isEqualByComparingTo("37.2");
		assertThat(center.get().longitude()).isEqualByComparingTo("127.2");
	}

	@Test
	@DisplayName("bbox가 없거나 PNU가 다른 feature만 있으면 parcel 좌표를 반환하지 않는다")
	void returnsEmptyWhenNoUsableParcelFeatureExists() {
		var center = policy.parcelCenter(
			"1168010300107770001",
			List.of(
				new CoordinateFeatureCandidate("1168010300107770001", List.of(new BigDecimal("127.0"))),
				feature("9999999999999999999", "127.0", "37.0", "127.2", "37.2")
			)
		);

		assertThat(center).isEmpty();
	}

	@Test
	@DisplayName("feature PNU가 비어 있으면 expected PNU와 충돌하지 않는 bbox로 취급한다")
	void acceptsBlankFeaturePnuAsNonConflictingParcelFeature() {
		var center = policy.parcelCenter(
			"1168010300107770001",
			List.of(feature(null, "127.0", "37.0", "127.2", "37.2"))
		);

		assertThat(center).isPresent();
		assertThat(center.get().latitude()).isEqualByComparingTo("37.1");
		assertThat(center.get().longitude()).isEqualByComparingTo("127.1");
	}

	@Test
	@DisplayName("building footprint feature는 exact PNU와 source key가 있을 때 import candidate로 변환한다")
	void mapsBuildingFootprintFeatures() {
		List<BuildingFootprintImportCandidate> footprints = policy.footprints(
			"4128510200115660000",
			"VWORLD_WFS",
			"LIVE",
			List.of(
				new BuildingFootprintFeatureCandidate(
					"dt_d010.1",
					"4128510200115660000",
					"중산마을",
					"1001동",
					List.of("G-1001", "R-1001"),
					bbox("126.7780", "37.6875", "126.7782", "37.6877")
				),
				new BuildingFootprintFeatureCandidate(
					"dt_d010.other",
					"9999999999999999999",
					null,
					"9999동",
					List.of(),
					bbox("126.0", "37.0", "126.1", "37.1")
				)
			)
		);

		assertThat(footprints).hasSize(1);
		assertThat(footprints.get(0).pnu()).isEqualTo("4128510200115660000");
		assertThat(footprints.get(0).buildingName()).isEqualTo("중산마을");
		assertThat(footprints.get(0).dongName()).isEqualTo("1001동");
		assertThat(footprints.get(0).sourceBuildingKey()).isEqualTo("dt_d010.1");
		assertThat(footprints.get(0).latitude()).isEqualByComparingTo("37.6876");
		assertThat(footprints.get(0).longitude()).isEqualByComparingTo("126.7781");
	}

	@Test
	@DisplayName("building footprint source key는 feature id가 없으면 대체 key를 사용한다")
	void usesFallbackBuildingKeyWhenFeatureIdIsMissing() {
		List<BuildingFootprintImportCandidate> footprints = policy.footprints(
			"4128510200115660000",
			"VWORLD_WFS",
			"LIVE",
			List.of(new BuildingFootprintFeatureCandidate(
				null,
				"4128510200115660000",
				null,
				"1001동",
				List.of("G-1001"),
				bbox("126.7780", "37.6875", "126.7782", "37.6877")
			))
		);

		assertThat(footprints).hasSize(1);
		assertThat(footprints.get(0).sourceBuildingKey()).isEqualTo("G-1001");
	}

	private CoordinateFeatureCandidate feature(
		String pnu,
		String minLongitude,
		String minLatitude,
		String maxLongitude,
		String maxLatitude
	) {
		return new CoordinateFeatureCandidate(pnu, bbox(minLongitude, minLatitude, maxLongitude, maxLatitude));
	}

	private List<BigDecimal> bbox(
		String minLongitude,
		String minLatitude,
		String maxLongitude,
		String maxLatitude
	) {
		return List.of(
			new BigDecimal(minLongitude),
			new BigDecimal(minLatitude),
			new BigDecimal(maxLongitude),
			new BigDecimal(maxLatitude)
		);
	}
}
