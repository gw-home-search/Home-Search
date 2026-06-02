package com.home.application.coordinate;

public interface ComplexCoordinateIdentityVerifier {

	ComplexCoordinateIdentityVerification verify(
		ComplexCoordinateParcelTargets parcelTargets,
		ComplexCoordinateTarget target
	);

	static ComplexCoordinateIdentityVerifier trusting() {
		return (parcelTargets, target) -> ComplexCoordinateIdentityVerification.confirmed("identity verifier not configured");
	}
}
