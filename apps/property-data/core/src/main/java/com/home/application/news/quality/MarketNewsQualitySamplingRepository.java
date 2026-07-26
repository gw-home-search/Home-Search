package com.home.application.news.quality;

import java.util.UUID;

public interface MarketNewsQualitySamplingRepository {

    MarketNewsQualitySampleResult createDeterministicSample(UUID reviewSetId, String policyVersion);
}
