package com.home.application.insight.port;

import com.home.domain.user.insight.InsightSubscription;
import java.time.Instant;
import java.util.Optional;

public interface InsightSubscriptionRepository {
    Optional<InsightSubscription> findEffective(long userId, String currentEmail);

    InsightSubscription save(InsightSubscription subscription, String currentEmail, Instant consentedAt);
}
