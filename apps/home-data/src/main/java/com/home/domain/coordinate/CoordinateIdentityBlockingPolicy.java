package com.home.domain.coordinate;

import java.util.Objects;

/**
 * 외부 단지 식별 검증 결과가 표시 좌표 확정을 막아야 하는지 결정하는 domain policy입니다.
 */
public record CoordinateIdentityBlockingPolicy(
	boolean blockOnUnavailable,
	boolean blockOnFailed
) {

	public CoordinateIdentityBlockingPolicy {
	}

	public static CoordinateIdentityBlockingPolicy strict() {
		return new CoordinateIdentityBlockingPolicy(true, true);
	}

	public static CoordinateIdentityBlockingPolicy degradeUnavailableAndFailed() {
		return new CoordinateIdentityBlockingPolicy(false, false);
	}

	public boolean shouldBlock(ComplexCoordinateIdentityVerificationStatus status) {
		Objects.requireNonNull(status, "status is required");
		return switch (status) {
			case CONFIRMED -> false;
			case AMBIGUOUS -> true;
			case UNAVAILABLE -> blockOnUnavailable;
			case FAILED -> blockOnFailed;
		};
	}
}
