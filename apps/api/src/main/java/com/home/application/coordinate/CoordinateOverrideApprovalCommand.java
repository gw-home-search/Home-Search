package com.home.application.coordinate;

import java.math.BigDecimal;

public record CoordinateOverrideApprovalCommand(
	String pnu,
	BigDecimal latitude,
	BigDecimal longitude,
	String reason,
	String approvedBy
) {
}
