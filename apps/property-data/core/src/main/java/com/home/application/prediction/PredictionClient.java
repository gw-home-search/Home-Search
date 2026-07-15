package com.home.application.prediction;

public interface PredictionClient {

    PredictionClientResult predict(PredictionRequest request);
}
