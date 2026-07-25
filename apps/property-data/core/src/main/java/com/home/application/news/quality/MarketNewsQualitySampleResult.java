package com.home.application.news.quality;

import com.home.domain.news.MarketNewsQualityReviewStatus;
import java.util.UUID;

public record MarketNewsQualitySampleResult(
        UUID reviewSetId,
        MarketNewsQualityReviewStatus status,
        int totalSampleCount,
        int minimumCategoryCount,
        int coveredSidoCount,
        int directComplexCount,
        int sameDongCount,
        int sameSigunguCount,
        int complexChallengeCount,
        int urlSampleCount) {}
