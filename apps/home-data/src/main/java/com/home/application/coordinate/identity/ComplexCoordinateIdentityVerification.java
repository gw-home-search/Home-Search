package com.home.application.coordinate.identity;

import java.util.Objects;

import com.home.domain.coordinate.ComplexCoordinateIdentityVerificationStatus;

public record ComplexCoordinateIdentityVerification(
	ComplexCoordinateIdentityVerificationStatus status,
	String reason
) {

	public ComplexCoordinateIdentityVerification {
		Objects.requireNonNull(status, "status is required");
	}

	public static ComplexCoordinateIdentityVerification confirmed(String reason) {
		return new ComplexCoordinateIdentityVerification(
			ComplexCoordinateIdentityVerificationStatus.CONFIRMED,
			reason
		);
	}

	public static ComplexCoordinateIdentityVerification ambiguous(String reason) {
		return new ComplexCoordinateIdentityVerification(
			ComplexCoordinateIdentityVerificationStatus.AMBIGUOUS,
			reason
		);
	}

	public static ComplexCoordinateIdentityVerification unavailable(String reason) {
		return new ComplexCoordinateIdentityVerification(
			ComplexCoordinateIdentityVerificationStatus.UNAVAILABLE,
			reason
		);
	}

	public static ComplexCoordinateIdentityVerification failed(String reason) {
		return new ComplexCoordinateIdentityVerification(
			ComplexCoordinateIdentityVerificationStatus.FAILED,
			reason
		);
	}
}
