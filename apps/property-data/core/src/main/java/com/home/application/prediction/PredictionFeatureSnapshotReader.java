package com.home.application.prediction;

import java.time.YearMonth;
import java.util.Optional;

public interface PredictionFeatureSnapshotReader {

    Optional<PredictionFeatureSnapshot> readSnapshot(PredictionBasis basis, YearMonth anchorMonth);
}
