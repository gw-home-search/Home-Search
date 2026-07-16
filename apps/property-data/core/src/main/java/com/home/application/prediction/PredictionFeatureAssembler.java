package com.home.application.prediction;

import java.util.Objects;

public final class PredictionFeatureAssembler {

    public PredictionFeature assemble(PredictionBasis basis, PredictionFeatureSnapshot snapshot) {
        Objects.requireNonNull(basis);
        Objects.requireNonNull(snapshot);
        return new PredictionFeature(
                basis.complexId(),
                basis.tradeId(),
                basis.dealDate(),
                basis.areaM2(),
                basis.floor(),
                snapshot.numericFeatures(),
                snapshot.embeddingFeatures(),
                snapshot.baseLogValue());
    }
}
