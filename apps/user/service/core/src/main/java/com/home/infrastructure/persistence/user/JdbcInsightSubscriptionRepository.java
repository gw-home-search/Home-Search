package com.home.infrastructure.persistence.user;

import com.home.application.insight.port.InsightSubscriptionRepository;
import com.home.domain.user.insight.InsightSubscription;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInsightSubscriptionRepository implements InsightSubscriptionRepository {

    private final JdbcClient jdbcClient;

    public JdbcInsightSubscriptionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<InsightSubscription> findEffective(long userId, String currentEmail) {
        return jdbcClient
                .sql("""
                    SELECT in_app_enabled,
                           email_enabled,
                           daily_news_enabled,
                           weekly_trade_enabled,
                           region_codes,
                           email_consent_hash
                    FROM users.insight_subscription
                    WHERE user_id = :userId
                    """)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> {
                    String[] regionCodes =
                            (String[]) resultSet.getArray("region_codes").getArray();
                    String consentHash = resultSet.getString("email_consent_hash");
                    boolean effectiveEmail = resultSet.getBoolean("email_enabled")
                            && currentEmail != null
                            && constantTimeEquals(consentHash, emailHash(currentEmail));
                    return new InsightSubscription(
                            userId,
                            resultSet.getBoolean("in_app_enabled"),
                            effectiveEmail,
                            resultSet.getBoolean("daily_news_enabled"),
                            resultSet.getBoolean("weekly_trade_enabled"),
                            regionCodes == null ? List.of() : List.of(regionCodes));
                })
                .optional();
    }

    @Override
    public InsightSubscription save(InsightSubscription subscription, String currentEmail, Instant consentedAt) {
        String consentHash = subscription.emailEnabled() ? emailHash(currentEmail) : null;
        jdbcClient
                .sql("""
                    INSERT INTO users.insight_subscription (
                        user_id,
                        in_app_enabled,
                        email_enabled,
                        daily_news_enabled,
                        weekly_trade_enabled,
                        region_codes,
                        email_consent_hash,
                        email_consented_at,
                        updated_at
                    ) VALUES (
                        :userId,
                        :inAppEnabled,
                        :emailEnabled,
                        :dailyNewsEnabled,
                        :weeklyTradeEnabled,
                        :regionCodes,
                        :emailConsentHash,
                        :emailConsentedAt,
                        :updatedAt
                    )
                    ON CONFLICT (user_id) DO UPDATE SET
                        in_app_enabled = EXCLUDED.in_app_enabled,
                        email_enabled = EXCLUDED.email_enabled,
                        daily_news_enabled = EXCLUDED.daily_news_enabled,
                        weekly_trade_enabled = EXCLUDED.weekly_trade_enabled,
                        region_codes = EXCLUDED.region_codes,
                        email_consent_hash = EXCLUDED.email_consent_hash,
                        email_consented_at = EXCLUDED.email_consented_at,
                        updated_at = EXCLUDED.updated_at
                    """)
                .param("userId", subscription.userId())
                .param("inAppEnabled", subscription.inAppEnabled())
                .param("emailEnabled", subscription.emailEnabled())
                .param("dailyNewsEnabled", subscription.dailyNewsEnabled())
                .param("weeklyTradeEnabled", subscription.weeklyTradeEnabled())
                .param("regionCodes", subscription.regionCodes().toArray(String[]::new))
                .param("emailConsentHash", consentHash)
                .param(
                        "emailConsentedAt",
                        subscription.emailEnabled() ? OffsetDateTime.ofInstant(consentedAt, ZoneOffset.UTC) : null)
                .param("updatedAt", OffsetDateTime.ofInstant(consentedAt, ZoneOffset.UTC))
                .update();
        return subscription;
    }

    private static String emailHash(String email) {
        if (email == null || email.isBlank()) return null;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(email.trim()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
