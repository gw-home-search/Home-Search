package com.home.infrastructure.persistence.user;

import com.home.application.insight.port.InsightInboxRepository;
import com.home.domain.user.insight.InsightInboxItem;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInsightInboxRepository implements InsightInboxRepository {

    private final JdbcClient jdbcClient;

    public JdbcInsightInboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public InboxPage list(long userId, int page, int size) {
        List<InsightInboxItem> content = jdbcClient
                .sql("""
                    SELECT inbox_id,
                           digest_id,
                           title,
                           property_snapshot_id,
                           deep_link,
                           created_at,
                           expires_at
                    FROM users.insight_inbox
                    WHERE user_id = :userId
                      AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY created_at DESC, inbox_id DESC
                    LIMIT :limit
                    OFFSET :offset
                    """)
                .param("userId", userId)
                .param("limit", size)
                .param("offset", Math.multiplyExact(page, size))
                .query((resultSet, rowNumber) -> new InsightInboxItem(
                        resultSet.getObject("inbox_id", UUID.class),
                        userId,
                        resultSet.getObject("digest_id", UUID.class),
                        resultSet.getString("title"),
                        resultSet.getString("property_snapshot_id"),
                        resultSet.getString("deep_link"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
        long total =
                jdbcClient.sql("""
                    SELECT count(*)
                    FROM users.insight_inbox
                    WHERE user_id = :userId
                      AND expires_at > CURRENT_TIMESTAMP
                    """).param("userId", userId).query(Long.class).single();
        return new InboxPage(content, total);
    }
}
