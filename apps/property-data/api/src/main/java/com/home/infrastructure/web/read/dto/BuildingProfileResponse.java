package com.home.infrastructure.web.read.dto;

import com.home.application.read.BuildingProfileSummaryResult;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicQuality;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicScope;
import com.home.domain.complex.buildingprofile.BuildingProfileSeismicDesignStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BuildingProfileResponse(
        Ratios ratios,
        Households households,
        Parking parking,
        Building building,
        Elevators elevators,
        Safety safety,
        Dates dates,
        Address address,
        Energy energy) {

    public static BuildingProfileResponse from(BuildingProfileSummaryResult value) {
        if (value == null) return null;
        return new BuildingProfileResponse(
                Ratios.from(value.ratios()), Households.from(value.households()), Parking.from(value.parking()),
                Building.from(value.building()), Elevators.from(value.elevators()), Safety.from(value.safety()),
                Dates.from(value.dates()), Address.from(value.address()), Energy.from(value.energy()));
    }

    public record Ratios(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            BigDecimal buildingCoverageRate, BigDecimal floorAreaRatio, BigDecimal siteAreaM2,
            BigDecimal buildingAreaM2, BigDecimal totalFloorAreaM2, BigDecimal floorAreaRatioAreaM2) {
        static Ratios from(BuildingProfileSummaryResult.Ratios v) { return v == null ? null : new Ratios(
                v.scope(), v.quality(), v.buildingCoverageRate(), v.floorAreaRatio(), v.siteAreaM2(),
                v.buildingAreaM2(), v.totalFloorAreaM2(), v.floorAreaRatioAreaM2()); }
    }

    public record Households(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            Long householdCount, Long familyCount, Long unitCount) {
        static Households from(BuildingProfileSummaryResult.Households v) { return v == null ? null : new Households(
                v.scope(), v.quality(), v.householdCount(), v.familyCount(), v.unitCount()); }
    }

    public record Parking(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality, Long totalCount,
            BigDecimal perHousehold, Long indoorMechanicalCount, BigDecimal indoorMechanicalAreaM2,
            Long outdoorMechanicalCount, BigDecimal outdoorMechanicalAreaM2, Long indoorAutomaticCount,
            BigDecimal indoorAutomaticAreaM2, Long outdoorAutomaticCount, BigDecimal outdoorAutomaticAreaM2) {
        static Parking from(BuildingProfileSummaryResult.Parking v) { return v == null ? null : new Parking(
                v.scope(), v.quality(), v.totalCount(), v.perHousehold(), v.indoorMechanicalCount(),
                v.indoorMechanicalAreaM2(), v.outdoorMechanicalCount(), v.outdoorMechanicalAreaM2(),
                v.indoorAutomaticCount(), v.indoorAutomaticAreaM2(), v.outdoorAutomaticCount(),
                v.outdoorAutomaticAreaM2()); }
    }

    public record Building(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            Long mainBuildingCount, Long attachedBuildingCount, Long maxGroundFloorCount,
            Long maxUndergroundFloorCount, BigDecimal maxHeightM, List<String> structures,
            List<String> roofs, List<String> primaryUses) {
        static Building from(BuildingProfileSummaryResult.Building v) { return v == null ? null : new Building(
                v.scope(), v.quality(), v.mainBuildingCount(), v.attachedBuildingCount(), v.maxGroundFloorCount(),
                v.maxUndergroundFloorCount(), v.maxHeightM(), v.structures(), v.roofs(), v.primaryUses()); }
    }

    public record Elevators(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            Long rideUseCount, Long emergencyUseCount) {
        static Elevators from(BuildingProfileSummaryResult.Elevators v) { return v == null ? null : new Elevators(
                v.scope(), v.quality(), v.rideUseCount(), v.emergencyUseCount()); }
    }

    public record Safety(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            BuildingProfileSeismicDesignStatus seismicDesignStatus, List<String> seismicAbilities) {
        static Safety from(BuildingProfileSummaryResult.Safety v) { return v == null ? null : new Safety(
                v.scope(), v.quality(), v.seismicDesignStatus(), v.seismicAbilities()); }
    }

    public record Dates(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            LocalDate permitDate, LocalDate constructionStartDate, LocalDate useApprovalDate) {
        static Dates from(BuildingProfileSummaryResult.Dates v) { return v == null ? null : new Dates(
                v.scope(), v.quality(), v.permitDate(), v.constructionStartDate(), v.useApprovalDate()); }
    }

    public record Address(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            String parcelAddress, String roadAddress) {
        static Address from(BuildingProfileSummaryResult.Address v) { return v == null ? null : new Address(
                v.scope(), v.quality(), v.parcelAddress(), v.roadAddress()); }
    }

    public record Energy(BuildingProfilePublicScope scope, BuildingProfilePublicQuality quality,
            List<String> efficiencyGrades, BigDecimal savingRateMin, BigDecimal savingRateMax,
            BigDecimal epiMin, BigDecimal epiMax, List<String> greenGrades, BigDecimal greenScoreMin,
            BigDecimal greenScoreMax, List<String> intelligentGrades, BigDecimal intelligentScoreMin,
            BigDecimal intelligentScoreMax) {
        static Energy from(BuildingProfileSummaryResult.Energy v) { return v == null ? null : new Energy(
                v.scope(), v.quality(), v.efficiencyGrades(), v.savingRateMin(), v.savingRateMax(), v.epiMin(),
                v.epiMax(), v.greenGrades(), v.greenScoreMin(), v.greenScoreMax(), v.intelligentGrades(),
                v.intelligentScoreMin(), v.intelligentScoreMax()); }
    }
}
