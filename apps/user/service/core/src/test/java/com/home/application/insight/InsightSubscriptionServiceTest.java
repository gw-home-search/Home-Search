package com.home.application.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.insight.port.InsightSubscriptionRepository;
import com.home.application.user.OAuthLoginResult;
import com.home.application.user.port.UserRepository;
import com.home.domain.user.OAuthProvider;
import com.home.domain.user.UserProfile;
import com.home.domain.user.insight.InsightSubscription;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsightSubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");

    @Test
    @DisplayName("저장값이 없으면 all-disabled 설정을 반환한다")
    void returnsDisabledSettingsWhenMissing() {
        RecordingSubscriptionRepository subscriptions = new RecordingSubscriptionRepository();
        InsightSubscriptionService service =
                new InsightSubscriptionService(subscriptions, userRepository("user@example.com"), fixedClock());

        assertThat(service.get(42)).isEqualTo(InsightSubscription.disabled(42));
    }

    @Test
    @DisplayName("email opt-in은 현재 계정 email에 대한 명시적 PUT 동의를 evidence로 저장한다")
    void recordsConsentForCurrentEmail() {
        RecordingSubscriptionRepository subscriptions = new RecordingSubscriptionRepository();
        InsightSubscriptionService service =
                new InsightSubscriptionService(subscriptions, userRepository("user@example.com"), fixedClock());

        InsightSubscription saved =
                service.update(42, new InsightSubscriptionUpdate(true, true, true, true, List.of("11", "41")));

        assertThat(saved.emailEnabled()).isTrue();
        assertThat(subscriptions.consentedEmail).isEqualTo("user@example.com");
        assertThat(subscriptions.savedAt).isEqualTo(NOW);
    }

    @Test
    @DisplayName("email이 없거나 SIDO 목록이 유효하지 않으면 설정을 저장하지 않는다")
    void rejectsMissingEmailAndInvalidRegions() {
        RecordingSubscriptionRepository subscriptions = new RecordingSubscriptionRepository();
        InsightSubscriptionService withoutEmail =
                new InsightSubscriptionService(subscriptions, userRepository(null), fixedClock());

        assertThatThrownBy(() ->
                        withoutEmail.update(42, new InsightSubscriptionUpdate(true, true, true, true, List.of("11"))))
                .isInstanceOf(EmailConsentRequiredException.class);
        assertThatThrownBy(() -> new InsightSubscriptionUpdate(
                        true, false, true, true, List.of("11", "26", "27", "28", "29", "30")))
                .isInstanceOf(InvalidInsightSubscriptionException.class);
        assertThatThrownBy(() -> new InsightSubscriptionUpdate(true, false, true, true, List.of("11", "11")))
                .isInstanceOf(InvalidInsightSubscriptionException.class);
        assertThat(subscriptions.saved).isNull();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static UserRepository userRepository(String email) {
        return new UserRepository() {
            @Override
            public Optional<OAuthLoginResult> findByUserId(long userId) {
                return Optional.of(
                        new OAuthLoginResult(userId, OAuthProvider.GOOGLE, new UserProfile("사용자", email, null)));
            }

            @Override
            public Optional<OAuthLoginResult> findByIdentity(com.home.domain.user.OAuthIdentityKey identity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OAuthLoginResult create(
                    com.home.domain.user.OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OAuthLoginResult updateProfile(
                    com.home.domain.user.OAuthIdentityKey identity, UserProfile profile, Instant loginAt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class RecordingSubscriptionRepository implements InsightSubscriptionRepository {
        private InsightSubscription saved;
        private String consentedEmail;
        private Instant savedAt;

        @Override
        public Optional<InsightSubscription> findEffective(long userId, String currentEmail) {
            return Optional.empty();
        }

        @Override
        public InsightSubscription save(InsightSubscription subscription, String currentEmail, Instant consentedAt) {
            this.saved = subscription;
            this.consentedEmail = currentEmail;
            this.savedAt = consentedAt;
            return subscription;
        }
    }
}
