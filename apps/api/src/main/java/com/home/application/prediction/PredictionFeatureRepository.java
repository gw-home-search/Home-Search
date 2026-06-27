package com.home.application.prediction;

import java.time.YearMonth;
import java.util.Optional;

public interface PredictionFeatureRepository {

	Optional<PredictionFeature> findFeature(Long complexId, YearMonth anchorMonth);
}
