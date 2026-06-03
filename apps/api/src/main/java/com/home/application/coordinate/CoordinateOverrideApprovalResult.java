package com.home.application.coordinate;

import java.math.BigDecimal;

public record CoordinateOverrideApprovalResult(
	String pnu,
	BigDecimal latitude,
	BigDecimal longitude,
	boolean parcelUpdated
) {
}
