package com.home.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.insight.InsightEventVersionGapException;
import com.home.application.insight.InsightPublishedEventService;
import com.home.application.insight.PublishedInsightEvent;
import com.home.domain.user.insight.InsightSubscription;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

class JdbcInsightRepositoryTest {

    @Test
    @DisplayName("subscription email consent는 현재 email hash와 일치할 때만 활성화된다")
    void emailConsentIsBoundToCurrentAccountEmail() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String url = prepareDatabase(postgres);
            var runtime = new DriverManagerDataSource(url, "home_search_user_runtime", "runtime-test-only");
            var jdbc = JdbcClient.create(runtime);
            long userId = insertUser(url);
            var repository = new JdbcInsightSubscriptionRepository(jdbc);
            Instant now = Instant.parse("2026-07-25T03:00:00Z");
            var subscription = new InsightSubscription(userId, true, true, true, true, List.of("11", "41"));

            repository.save(subscription, "User@Example.com", now);

            assertThat(repository.findEffective(userId, "user@example.com")).contains(subscription);
            assertThat(repository.findEffective(userId, "changed@example.com"))
                    .contains(new InsightSubscription(userId, true, false, true, true, List.of("11", "41")));
        }
    }

    @Test
    @DisplayName("event 적용은 inbox와 projection을 원자적으로 저장하고 duplicate와 stale은 no-op한다")
    void appliesEventAtomicallyAndRejectsVersionGap() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            String url = prepareDatabase(postgres);
            var runtime = new DriverManagerDataSource(url, "home_search_user_runtime", "runtime-test-only");
            var jdbc = JdbcClient.create(runtime);
            long userId = insertUser(url);
            Instant now = Instant.parse("2026-07-25T03:00:00Z");
            new JdbcInsightSubscriptionRepository(jdbc)
                    .save(new InsightSubscription(userId, true, false, false, true, List.of("11")), null, now);
            var repository = new JdbcInsightEventRepository(jdbc);
            var service = new InsightPublishedEventService(repository, Clock.fixed(now, ZoneOffset.UTC));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(runtime));
            var initial = event("55555555-5555-4555-8555-555555555551", 3);

            Integer created = transaction.execute(status -> service.consume(initial));
            Integer duplicate = transaction.execute(status -> service.consume(initial));
            Integer stale =
                    transaction.execute(status -> service.consume(event("55555555-5555-4555-8555-555555555552", 2)));
            assertThat(created).isEqualTo(1);
            assertThat(duplicate).isZero();
            assertThat(stale).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM users.insight_inbox")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
            assertThat(jdbc.sql("""
                                SELECT aggregate_version
                                FROM users.event_projection_version
                                WHERE event_type = 'InsightPublished'
                                  AND aggregate_id = 'snapshot-aggregate-11'
                                """).query(Long.class).single()).isEqualTo(3);

            UUID gapEventId = UUID.fromString("55555555-5555-4555-8555-555555555553");
            assertThatThrownBy(() -> transaction.execute(status -> service.consume(event(gapEventId.toString(), 5))))
                    .isInstanceOf(InsightEventVersionGapException.class);
            assertThat(jdbc.sql("""
                                SELECT count(*)
                                FROM users.event_consumer_inbox
                                WHERE event_id = :eventId
                                """)
                            .param("eventId", gapEventId)
                            .query(Integer.class)
                            .single())
                    .isZero();

            UUID expiredInboxId = UUID.fromString("66666666-6666-4666-8666-666666666661");
            jdbc.sql("""
                        INSERT INTO users.insight_inbox(
                            inbox_id, user_id, digest_id, title, property_snapshot_id,
                            deep_link, created_at, expires_at
                        ) VALUES(
                            :inboxId, :userId, '66666666-6666-4666-8666-666666666662',
                            'expired', 'snapshot-expired', '/insights',
                            :createdAt, :expiresAt
                        )
                        """)
                    .param("inboxId", expiredInboxId)
                    .param("userId", userId)
                    .param("createdAt", java.time.OffsetDateTime.parse("2026-04-01T03:00:00Z"))
                    .param("expiresAt", java.time.OffsetDateTime.parse("2026-07-24T03:00:00Z"))
                    .update();
            jdbc.sql("""
                        INSERT INTO users.event_consumer_inbox(
                            event_id, topic_name, event_type, aggregate_id, aggregate_version,
                            received_at, processed_at, expires_at
                        ) VALUES(
                            '66666666-6666-4666-8666-666666666663',
                            'property.insight-events.v1', 'InsightPublished', 'expired', 1,
                            :receivedAt, :processedAt, :expiresAt
                        )
                        """)
                    .param("receivedAt", java.time.OffsetDateTime.parse("2026-05-01T03:00:00Z"))
                    .param("processedAt", java.time.OffsetDateTime.parse("2026-05-01T03:00:00Z"))
                    .param("expiresAt", java.time.OffsetDateTime.parse("2026-07-24T03:00:00Z"))
                    .update();

            var page = new JdbcInsightInboxRepository(jdbc).list(userId, 0, 20);
            assertThat(page.totalElements()).isEqualTo(1);
            assertThat(page.content())
                    .singleElement()
                    .satisfies(item -> assertThat(item.inboxId()).isNotEqualTo(expiredInboxId));
            assertThat(new JdbcInsightRetentionRepository(jdbc).deleteExpired(now))
                    .isEqualTo(new com.home.application.insight.port.InsightRetentionRepository.RetentionResult(1, 1));
        }
    }

    private static PublishedInsightEvent event(String eventId, long aggregateVersion) {
        return new PublishedInsightEvent(
                UUID.fromString(eventId),
                "InsightPublished",
                "snapshot-aggregate-11",
                aggregateVersion,
                UUID.fromString("55555555-aaaa-4555-8555-555555555555"),
                "ROLLING_7D",
                "SIDO",
                "11");
    }

    private static String prepareDatabase(PostgreSQLContainer<?> postgres) throws Exception {
        try (var admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = admin.createStatement()) {
            admin.setAutoCommit(true);
            statement.execute("CREATE ROLE home_search_user_migrator LOGIN PASSWORD 'migrator-test-only'");
            statement.execute("CREATE ROLE home_search_user_runtime LOGIN PASSWORD 'runtime-test-only'");
            statement.execute("CREATE DATABASE home_search_user OWNER home_search_user_migrator");
        }
        String url =
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/home_search_user";
        Flyway.configure()
                .dataSource(url, "home_search_user_migrator", "migrator-test-only")
                .locations(System.getProperty("userServiceMigrationLocation"))
                .schemas("users")
                .defaultSchema("users")
                .load()
                .migrate();
        return url;
    }

    private static long insertUser(String url) throws Exception {
        try (var migrator = DriverManager.getConnection(url, "home_search_user_migrator", "migrator-test-only");
                var result = migrator.createStatement()
                        .executeQuery(
                                "INSERT INTO users.user_account(role,display_name,email,created_at,updated_at) VALUES('USER','insight-test','user@example.com',now(),now()) RETURNING id")) {
            result.next();
            return result.getLong(1);
        }
    }
}
