package com.home.application.prediction;

import java.util.Optional;

public interface PredictionBasisReader {

    Optional<PredictionBasis> findBasis(Long complexId);
}
