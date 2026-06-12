package com.home.application.region;

public record RegionUnitCntSyncResult(
	boolean partial,
	boolean relationChanged,
	boolean unitCntChanged,
	boolean unmatchedParcelExists
) {
}
