package com.home.user.web;

import com.home.application.insight.InsightInboxService;
import com.home.application.insight.InsightSubscriptionService;
import com.home.application.insight.InsightSubscriptionUpdate;
import com.home.domain.user.insight.InsightInboxItem;
import com.home.domain.user.insight.InsightSubscription;
import com.home.user.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "home.insights", name = "enabled", havingValue = "true")
public class InsightController {

    private final InsightSubscriptionService subscriptions;
    private final InsightInboxService inbox;

    public InsightController(InsightSubscriptionService subscriptions, InsightInboxService inbox) {
        this.subscriptions = subscriptions;
        this.inbox = inbox;
    }

    @GetMapping("/api/v1/insights/subscription")
    SubscriptionResponse getSubscription(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return response(subscriptions.get(principal.userId()));
    }

    @PutMapping("/api/v1/insights/subscription")
    SubscriptionResponse updateSubscription(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @Valid @RequestBody SubscriptionRequest request) {
        return response(subscriptions.update(
                principal.userId(),
                new InsightSubscriptionUpdate(
                        request.inAppEnabled(),
                        request.emailEnabled(),
                        request.dailyNewsEnabled(),
                        request.weeklyTradeEnabled(),
                        request.regionCodes())));
    }

    @GetMapping("/api/v1/insights/inbox")
    InboxPageResponse getInbox(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = inbox.list(principal.userId(), page, size);
        int totalPages = result.totalElements() == 0 ? 0 : (int) ((result.totalElements() + size - 1) / size);
        return new InboxPageResponse(
                result.content().stream().map(InsightController::response).toList(),
                page,
                size,
                result.totalElements(),
                totalPages);
    }

    private static SubscriptionResponse response(InsightSubscription value) {
        return new SubscriptionResponse(
                value.inAppEnabled(),
                value.emailEnabled(),
                value.dailyNewsEnabled(),
                value.weeklyTradeEnabled(),
                value.regionCodes());
    }

    private static InboxItemResponse response(InsightInboxItem value) {
        return new InboxItemResponse(
                value.inboxId(),
                value.digestId(),
                value.title(),
                value.propertySnapshotId(),
                value.deepLink(),
                value.createdAt(),
                value.expiresAt());
    }

    record SubscriptionRequest(
            boolean inAppEnabled,
            boolean emailEnabled,
            boolean dailyNewsEnabled,
            boolean weeklyTradeEnabled,
            @NotNull List<String> regionCodes) {}

    record SubscriptionResponse(
            boolean inAppEnabled,
            boolean emailEnabled,
            boolean dailyNewsEnabled,
            boolean weeklyTradeEnabled,
            List<String> regionCodes) {}

    record InboxItemResponse(
            UUID inboxId,
            UUID digestId,
            String title,
            String propertySnapshotId,
            String deepLink,
            Instant createdAt,
            Instant expiresAt) {}

    record InboxPageResponse(List<InboxItemResponse> content, int page, int size, long totalElements, int totalPages) {}
}
