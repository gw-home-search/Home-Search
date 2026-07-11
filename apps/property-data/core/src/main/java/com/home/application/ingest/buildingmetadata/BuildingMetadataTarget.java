package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataValues;

public record BuildingMetadataTarget(
	long complexId,
	String pnu,
	int pnuComplexCount,
	String buildingRegisterKey,
	BuildingMetadataValues currentValues
) {}
