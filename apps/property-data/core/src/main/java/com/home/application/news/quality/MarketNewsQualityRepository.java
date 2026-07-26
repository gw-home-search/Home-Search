package com.home.application.news.quality;

import com.home.domain.news.MarketNewsWithdrawalReason;
import java.util.Optional;
import java.util.UUID;

public interface MarketNewsQualityRepository {

    Optional<WithdrawnNewsSnapshot> withdrawPublished(UUID snapshotId, MarketNewsWithdrawalReason reason);
}
