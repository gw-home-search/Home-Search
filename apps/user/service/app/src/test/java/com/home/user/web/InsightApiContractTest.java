package com.home.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.home.application.insight.InsightInboxService;
import com.home.application.insight.InsightSubscriptionService;
import com.home.application.insight.InsightSubscriptionUpdate;
import com.home.application.insight.port.InsightInboxRepository.InboxPage;
import com.home.domain.user.insight.InsightInboxItem;
import com.home.domain.user.insight.InsightSubscription;
import com.home.user.security.AuthenticatedUserPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class InsightApiContractTest {

    @Test
    @DisplayName("insight public API는 staging E2E 승인 전 기본 비활성이다")
    void requiresExplicitFeatureOptIn() {
        ConditionalOnProperty condition = InsightController.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("home.insights");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    @DisplayName("subscription API는 JWT principal만 user id로 사용하고 승인된 field shape를 유지한다")
    void exposesSubscriptionWithoutAcceptingUserId() {
        InsightSubscriptionService subscriptions = mock(InsightSubscriptionService.class);
        InsightInboxService inbox = mock(InsightInboxService.class);
        InsightSubscription value = new InsightSubscription(42, true, false, true, true, List.of("11", "41"));
        when(subscriptions.get(42)).thenReturn(value);
        when(subscriptions.update(42, new InsightSubscriptionUpdate(true, false, true, true, List.of("11", "41"))))
                .thenReturn(value);
        InsightController controller = new InsightController(subscriptions, inbox);
        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(42);

        assertThat(controller.getSubscription(principal))
                .isEqualTo(new InsightController.SubscriptionResponse(true, false, true, true, List.of("11", "41")));
        assertThat(controller.updateSubscription(
                        principal,
                        new InsightController.SubscriptionRequest(true, false, true, true, List.of("11", "41"))))
                .isEqualTo(new InsightController.SubscriptionResponse(true, false, true, true, List.of("11", "41")));
    }

    @Test
    @DisplayName("inbox API는 newest-first page envelope와 snapshot deep link field를 반환한다")
    void exposesInboxPageEnvelope() {
        Instant createdAt = Instant.parse("2026-07-25T03:00:00Z");
        Instant expiresAt = Instant.parse("2026-10-23T03:00:00Z");
        UUID inboxId = UUID.fromString("66666666-aaaa-4666-8666-666666666666");
        UUID digestId = UUID.fromString("77777777-aaaa-4777-8777-777777777777");
        InsightSubscriptionService subscriptions = mock(InsightSubscriptionService.class);
        InsightInboxService inbox = mock(InsightInboxService.class);
        when(inbox.list(42, 0, 20))
                .thenReturn(new InboxPage(
                        List.of(new InsightInboxItem(
                                inboxId,
                                42,
                                digestId,
                                "서울 주간 거래 인사이트",
                                "snapshot-1",
                                "/insights?scope=SIDO&regionCode=11",
                                createdAt,
                                expiresAt)),
                        1));
        InsightController controller = new InsightController(subscriptions, inbox);

        var response = controller.getInbox(new AuthenticatedUserPrincipal(42), 0, 20);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content()).singleElement().satisfies(item -> {
            assertThat(item.inboxId()).isEqualTo(inboxId);
            assertThat(item.digestId()).isEqualTo(digestId);
            assertThat(item.propertySnapshotId()).isEqualTo("snapshot-1");
            assertThat(item.deepLink()).isEqualTo("/insights?scope=SIDO&regionCode=11");
        });
    }
}
