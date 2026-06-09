package com.home.domain.coordinate;

/**
 * 지도 표시 좌표가 운영 marker에 충분히 신뢰 가능한지 판단하는 domain policy입니다.
 */
public final class CoordinateDisplayPolicy {

	public static final int TRUSTED_BUILDING_FOOTPRINT_CONFIDENCE = 80;

	private CoordinateDisplayPolicy() {
	}

	public static boolean isTrustedBuildingFootprintConfidence(int confidence) {
		return confidence >= TRUSTED_BUILDING_FOOTPRINT_CONFIDENCE;
	}
}
