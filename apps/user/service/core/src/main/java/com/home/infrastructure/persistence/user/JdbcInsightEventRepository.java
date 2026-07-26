package com.home.infrastructure.persistence.user;

import com.home.application.insight.InsightEventVersionGapException;
import com.home.application.insight.PublishedInsightEvent;
import com.home.application.insight.port.InsightEventRepository;
import com.home.domain.user.insight.InsightInboxItem;
import com.home.domain.user.insight.InsightSubscription;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInsightEventRepository implements InsightEventRepository {

    private static final String TOPIC_NAME = "property.insight-events.v1";

    private final JdbcClient jdbcClient;

    public JdbcInsightEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<InsightSubscription> findCandidates(String scopeType, String regionCode) {
        return jdbcClient
                .sql("""
                    SELECT user_id,
                           in_app_enabled,
                           email_enabled,
                           daily_news_enabled,
                           weekly_trade_enabled,
                           region_codes
                    FROM users.insight_subscription
                    WHERE in_app_enabled = TRUE
                      AND (
                          :scopeType = 'NATIONWIDE'
                          OR (:scopeType = 'SIDO' AND :regionCode = ANY(region_codes))
                      )
                    ORDER BY user_id
                    """)
                .param("scopeType", scopeType)
                .param("regionCode", regionCode)
                .query((resultSet, rowNumber) -> new InsightSubscription(
                        resultSet.getLong("user_id"),
                        resultSet.getBoolean("in_app_enabled"),
                        resultSet.getBoolean("email_enabled"),
                        resultSet.getBoolean("daily_news_enabled"),
                        resultSet.getBoolean("weekly_trade_enabled"),
                        regionCodes(resultSet.getArray("region_codes"))))
                .list();
    }

    @Override
    public boolean apply(PublishedInsightEvent event, List<InsightInboxItem> items, Instant processedAt) {
        int accepted = jdbcClient
                .sql("""
                    INSERT INTO users.event_consumer_inbox (
                        event_id,
                        topic_name,
                        event_type,
                        aggregate_id,
                        aggregate_version,
                        received_at,
                        processed_at,
                        expires_at
                    ) VALUES (
                        :eventId,
                        :topicName,
                        :eventType,
                        :aggregateId,
                        :aggregateVersion,
                        :processedAt,
                        :processedAt,
                        :expiresAt
                    )
                    ON CONFLICT (event_id) DO NOTHING
                    """)
                .param("eventId", event.eventId())
                .param("topicName", TOPIC_NAME)
                .param("eventType", event.eventType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("processedAt", timestamp(processedAt))
                .param("expiresAt", timestamp(processedAt.plusSeconds(45L * 86_400)))
                .update();
        if (accepted == 0) return false;

        lockAggregate(event);
        Long currentVersion = findCurrentVersion(event);
        if (currentVersion != null && event.aggregateVersion() <= currentVersion) return false;
        if (currentVersion != null && event.aggregateVersion() > currentVersion + 1) {
            throw new InsightEventVersionGapException(
                    event.eventType(), event.aggregateId(), currentVersion, event.aggregateVersion());
        }

        upsertProjectionVersion(event, processedAt);
        items.forEach(this::insertInboxItem);
        return true;
    }

    private void lockAggregate(PublishedInsightEvent event) {
        jdbcClient
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(:aggregateKey, 0))")
                .param("aggregateKey", event.eventType() + ":" + event.aggregateId())
                .query((resultSet, rowNumber) -> true)
                .single();
    }

    private Long findCurrentVersion(PublishedInsightEvent event) {
        return jdbcClient
                .sql("""
                    SELECT aggregate_version
                    FROM users.event_projection_version
                    WHERE event_type = :eventType
                      AND aggregate_id = :aggregateId
                    FOR UPDATE
                    """)
                .param("eventType", event.eventType())
                .param("aggregateId", event.aggregateId())
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private void upsertProjectionVersion(PublishedInsightEvent event, Instant processedAt) {
        jdbcClient
                .sql("""
                    INSERT INTO users.event_projection_version (
                        event_type,
                        aggregate_id,
                        aggregate_version,
                        updated_at
                    ) VALUES (
                        :eventType,
                        :aggregateId,
                        :aggregateVersion,
                        :updatedAt
                    )
                    ON CONFLICT (event_type, aggregate_id) DO UPDATE SET
                        aggregate_version = EXCLUDED.aggregate_version,
                        updated_at = EXCLUDED.updated_at
                    """)
                .param("eventType", event.eventType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("updatedAt", timestamp(processedAt))
                .update();
    }

    private void insertInboxItem(InsightInboxItem item) {
        jdbcClient
                .sql("""
                    INSERT INTO users.insight_inbox (
                        inbox_id,
                        user_id,
                        digest_id,
                        title,
                        property_snapshot_id,
                        deep_link,
                        created_at,
                        expires_at
                    ) VALUES (
                        :inboxId,
                        :userId,
                        :digestId,
                        :title,
                        :propertySnapshotId,
                        :deepLink,
                        :createdAt,
                        :expiresAt
                    )
                    ON CONFLICT (user_id, digest_id) DO NOTHING
                    """)
                .param("inboxId", item.inboxId())
                .param("userId", item.userId())
                .param("digestId", item.digestId())
                .param("title", item.title())
                .param("propertySnapshotId", item.propertySnapshotId())
                .param("deepLink", item.deepLink())
                .param("createdAt", timestamp(item.createdAt()))
                .param("expiresAt", timestamp(item.expiresAt()))
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static List<String> regionCodes(Array array) throws SQLException {
        if (array == null) return List.of();
        String[] values = (String[]) array.getArray();
        return values == null ? List.of() : List.of(values);
    }
}
