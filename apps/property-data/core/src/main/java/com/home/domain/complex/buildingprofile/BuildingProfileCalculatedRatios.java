package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;

public record BuildingProfileCalculatedRatios(
        BigDecimal buildingCoverageRatio, BigDecimal floorAreaRatio, boolean complete) {}
