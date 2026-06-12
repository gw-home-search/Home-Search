package com.home.application.region;

import java.util.Objects;

public class RegionUnitCntSynchronizationService {

	private final RegionRelationSynchronizationGateway gateway;

	public RegionUnitCntSynchronizationService(RegionRelationSynchronizationGateway gateway) {
		this.gateway = Objects.requireNonNull(gateway);
	}

	public RegionUnitCntSyncResult synchronize() {
		RegionRelationSynchronizationResult result = gateway.synchronizeAll();
		return new RegionUnitCntSyncResult(
			result.unmatchedParcelExists(),
			result.relationChanged(),
			result.unitCntChanged(),
			result.unmatchedParcelExists()
		);
	}
}
