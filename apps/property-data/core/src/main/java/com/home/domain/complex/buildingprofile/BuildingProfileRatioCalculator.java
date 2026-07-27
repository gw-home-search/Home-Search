package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BuildingProfileRatioCalculator {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public BuildingProfileCalculatedRatios calculate(
            BigDecimal consensusPlatArea, List<BuildingProfileAreaContribution> titles, boolean completeTitleSet) {
        List<BuildingProfileAreaContribution> safe = titles == null ? List.of() : List.copyOf(titles);
        if (!completeTitleSet || consensusPlatArea == null || consensusPlatArea.signum() <= 0 || safe.isEmpty()) {
            return new BuildingProfileCalculatedRatios(null, null, false);
        }
        Set<String> keys = new HashSet<>();
        BigDecimal architectural = BigDecimal.ZERO;
        BigDecimal floorEstimate = BigDecimal.ZERO;
        for (BuildingProfileAreaContribution title : safe) {
            if (!keys.add(title.managementKey())
                    || title.architecturalArea() == null
                    || title.floorRatioEstimateArea() == null) {
                return new BuildingProfileCalculatedRatios(null, null, false);
            }
            architectural = architectural.add(title.architecturalArea());
            floorEstimate = floorEstimate.add(title.floorRatioEstimateArea());
        }
        return new BuildingProfileCalculatedRatios(
                architectural.multiply(ONE_HUNDRED).divide(consensusPlatArea, MathContext.DECIMAL128),
                floorEstimate.multiply(ONE_HUNDRED).divide(consensusPlatArea, MathContext.DECIMAL128),
                true);
    }

    public BigDecimal buildingCoverageRatio(
            BigDecimal consensusPlatArea, List<BuildingProfileAreaContribution> titles, boolean completeTitleSet) {
        return calculateSingle(consensusPlatArea, titles, completeTitleSet, true);
    }

    public BigDecimal floorAreaRatio(
            BigDecimal consensusPlatArea, List<BuildingProfileAreaContribution> titles, boolean completeTitleSet) {
        return calculateSingle(consensusPlatArea, titles, completeTitleSet, false);
    }

    public BuildingProfileCalculatedRatios calculateFromCompleteTotals(
            BigDecimal consensusPlatArea, BigDecimal architecturalArea, BigDecimal floorRatioEstimateArea) {
        if (consensusPlatArea == null
                || consensusPlatArea.signum() <= 0
                || architecturalArea == null
                || architecturalArea.signum() <= 0
                || floorRatioEstimateArea == null
                || floorRatioEstimateArea.signum() <= 0) {
            return new BuildingProfileCalculatedRatios(null, null, false);
        }
        return new BuildingProfileCalculatedRatios(
                architecturalArea.multiply(ONE_HUNDRED).divide(consensusPlatArea, MathContext.DECIMAL128),
                floorRatioEstimateArea.multiply(ONE_HUNDRED).divide(consensusPlatArea, MathContext.DECIMAL128),
                true);
    }

    private BigDecimal calculateSingle(
            BigDecimal consensusPlatArea,
            List<BuildingProfileAreaContribution> titles,
            boolean completeTitleSet,
            boolean architectural) {
        List<BuildingProfileAreaContribution> safe = titles == null ? List.of() : List.copyOf(titles);
        if (!completeTitleSet || consensusPlatArea == null || consensusPlatArea.signum() <= 0 || safe.isEmpty()) {
            return null;
        }
        Set<String> keys = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (BuildingProfileAreaContribution title : safe) {
            BigDecimal contribution = architectural ? title.architecturalArea() : title.floorRatioEstimateArea();
            if (!keys.add(title.managementKey()) || contribution == null) return null;
            total = total.add(contribution);
        }
        return total.multiply(ONE_HUNDRED).divide(consensusPlatArea, MathContext.DECIMAL128);
    }
}
