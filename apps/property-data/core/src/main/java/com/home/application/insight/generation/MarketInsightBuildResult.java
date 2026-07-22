package com.home.application.insight.generation;

import com.home.domain.insight.MarketInsightRejectionReason;
import java.util.UUID;

public record MarketInsightBuildResult(
        UUID snapshotId, boolean published, MarketInsightRejectionReason rejectionReason) {

    public static MarketInsightBuildResult published(UUID snapshotId) {
        return new MarketInsightBuildResult(snapshotId, true, null);
    }

    public static MarketInsightBuildResult rejected(UUID snapshotId, MarketInsightRejectionReason reason) {
        return new MarketInsightBuildResult(snapshotId, false, reason);
    }
}
