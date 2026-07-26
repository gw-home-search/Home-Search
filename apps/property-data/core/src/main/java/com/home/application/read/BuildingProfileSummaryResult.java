package com.home.application.read;

import com.home.domain.complex.buildingprofile.BuildingProfilePublicQuality;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicScope;
import com.home.domain.complex.buildingprofile.BuildingProfileSeismicDesignStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BuildingProfileSummaryResult(
        Ratios ratios,
        Households households,
        Parking parking,
        Building building,
        Elevators elevators,
        Safety safety,
        Dates dates,
        Address address,
        Energy energy) {

    public record Ratios(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            BigDecimal buildingCoverageRate,
            BigDecimal floorAreaRatio,
            BigDecimal siteAreaM2,
            BigDecimal buildingAreaM2,
            BigDecimal totalFloorAreaM2,
            BigDecimal floorAreaRatioAreaM2) {}

    public record Households(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            Long householdCount,
            Long familyCount,
            Long unitCount) {}

    public record Parking(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            Long totalCount,
            BigDecimal perHousehold,
            Long indoorMechanicalCount,
            BigDecimal indoorMechanicalAreaM2,
            Long outdoorMechanicalCount,
            BigDecimal outdoorMechanicalAreaM2,
            Long indoorAutomaticCount,
            BigDecimal indoorAutomaticAreaM2,
            Long outdoorAutomaticCount,
            BigDecimal outdoorAutomaticAreaM2) {}

    public record Building(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            Long mainBuildingCount,
            Long attachedBuildingCount,
            Long maxGroundFloorCount,
            Long maxUndergroundFloorCount,
            BigDecimal maxHeightM,
            List<String> structures,
            List<String> roofs,
            List<String> primaryUses) {}

    public record Elevators(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            Long rideUseCount,
            Long emergencyUseCount) {}

    public record Safety(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            BuildingProfileSeismicDesignStatus seismicDesignStatus,
            List<String> seismicAbilities) {}

    public record Dates(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            LocalDate permitDate,
            LocalDate constructionStartDate,
            LocalDate useApprovalDate) {}

    public record Address(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            String parcelAddress,
            String roadAddress) {}

    public record Energy(
            BuildingProfilePublicScope scope,
            BuildingProfilePublicQuality quality,
            List<String> efficiencyGrades,
            BigDecimal savingRateMin,
            BigDecimal savingRateMax,
            BigDecimal epiMin,
            BigDecimal epiMax,
            List<String> greenGrades,
            BigDecimal greenScoreMin,
            BigDecimal greenScoreMax,
            List<String> intelligentGrades,
            BigDecimal intelligentScoreMin,
            BigDecimal intelligentScoreMax) {}
}
