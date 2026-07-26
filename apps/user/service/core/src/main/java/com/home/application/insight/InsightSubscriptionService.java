package com.home.application.insight;

import com.home.application.insight.port.InsightSubscriptionRepository;
import com.home.application.user.UserNotFoundException;
import com.home.application.user.port.UserRepository;
import com.home.domain.user.insight.InsightSubscription;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InsightSubscriptionService {

    private final InsightSubscriptionRepository subscriptions;
    private final UserRepository users;
    private final Clock clock;

    public InsightSubscriptionService(InsightSubscriptionRepository subscriptions, UserRepository users, Clock clock) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InsightSubscription get(long userId) {
        String email = currentEmail(userId);
        return subscriptions.findEffective(userId, email).orElseGet(() -> InsightSubscription.disabled(userId));
    }

    @Transactional
    public InsightSubscription update(long userId, InsightSubscriptionUpdate update) {
        String email = currentEmail(userId);
        if (update.emailEnabled() && email == null) {
            throw new EmailConsentRequiredException();
        }
        var subscription = new InsightSubscription(
                userId,
                update.inAppEnabled(),
                update.emailEnabled(),
                update.dailyNewsEnabled(),
                update.weeklyTradeEnabled(),
                update.regionCodes());
        return subscriptions.save(subscription, update.emailEnabled() ? email : null, clock.instant());
    }

    private String currentEmail(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return users.findByUserId(userId)
                .orElseThrow(UserNotFoundException::new)
                .profile()
                .email();
    }
}
