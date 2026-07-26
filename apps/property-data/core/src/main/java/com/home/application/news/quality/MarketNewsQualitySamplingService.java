package com.home.application.news.quality;

import java.util.Objects;
import java.util.UUID;

public final class MarketNewsQualitySamplingService {

    private final MarketNewsQualitySamplingRepository repository;

    public MarketNewsQualitySamplingService(MarketNewsQualitySamplingRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public MarketNewsQualitySampleResult sample(UUID reviewSetId, String policyVersion) {
        Objects.requireNonNull(reviewSetId, "reviewSetId is required");
        String normalizedPolicyVersion = Objects.requireNonNull(policyVersion, "policyVersion is required")
                .trim();
        if (!normalizedPolicyVersion.matches("NEWS_V[1-9][0-9]*")) {
            throw new IllegalArgumentException("policyVersion must be a versioned NEWS policy");
        }
        return repository.createDeterministicSample(reviewSetId, normalizedPolicyVersion);
    }
}
