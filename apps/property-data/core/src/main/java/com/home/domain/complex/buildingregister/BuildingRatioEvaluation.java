package com.home.domain.complex.buildingregister;

import java.util.EnumMap;
import java.util.Map;

public record BuildingRatioEvaluation(Map<BuildingRatioField, BuildingRatioFieldEvaluation> fields) {
    public BuildingRatioEvaluation {
        EnumMap<BuildingRatioField, BuildingRatioFieldEvaluation> copy = new EnumMap<>(BuildingRatioField.class);
        if (fields != null) copy.putAll(fields);
        fields = Map.copyOf(copy);
    }

    public BuildingRatioFieldEvaluation field(BuildingRatioField field) {
        BuildingRatioFieldEvaluation evaluation = fields.get(field);
        if (evaluation == null) throw new IllegalArgumentException("ratio field was not evaluated: " + field);
        return evaluation;
    }
}
