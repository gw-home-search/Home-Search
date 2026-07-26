package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.news.collection.NewsCallBudgetExceededException;
import com.home.application.news.collection.NewsProviderItem;
import com.home.application.news.collection.NormalizedNewsItem;
import com.home.application.news.read.MarketNewsCursor;
import com.home.application.news.selection.MajorNewsComplexSelectionService;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsFailureKind;
import com.home.domain.news.MarketNewsRelationMatch;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWithdrawalReason;
import com.home.domain.news.MarketNewsWorkUnitState;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMarketNewsRepositoryIntegrationTest extends JdbcPostgresTestSupport {

    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174200");
    private static final Instant GENERATED_AT = Instant.parse("2026-07-24T09:30:00Z");

    private JdbcMarketNewsCollectionRepository collectionRepository;

    @BeforeEach
    void setUpNewsEvidence() {
        seedPropertyExplorationData();
        jdbcClient.sql("UPDATE complex SET region_id = 111 WHERE id = 501").update();
        collectionRepository = new JdbcMarketNewsCollectionRepository(jdbcClient);
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget, call_count,
                        planned_work_unit_count, started_at
                    ) VALUES (
                        :executionId, 'NEWS-INTEGRATION', 'GENERAL', 'NEWS_V2',
                        :scheduledAt, :cutoff, 'RUNNING', 4000, 0, 6, :startedAt
                    )
                    """)
                .param("executionId", EXECUTION_ID)
                .param("scheduledAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .param("cutoff", GENERATED_AT.minusSeconds(7200).atOffset(ZoneOffset.UTC))
                .param("startedAt", GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        for (int order = 1; order <= 6; order++) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_collection_work_unit (
                            work_unit_id, execution_id, unit_order, scope_kind, scope_type,
                            category, query_text, cutoff_at, state, started_at
                        ) VALUES (
                            :workUnitId, :executionId, :unitOrder, 'NATIONAL_CATEGORY',
                            'NATIONWIDE', :category, :queryText, :cutoff, 'RUNNING', :startedAt
                        )
                        """)
                    .param("workUnitId", workUnitId(order))
                    .param("executionId", EXECUTION_ID)
                    .param("unitOrder", order)
                    .param("category", categories().get(order - 1).name())
                    .param("queryText", "test query " + order)
                    .param("cutoff", GENERATED_AT.minusSeconds(7200).atOffset(ZoneOffset.UTC))
                    .param("startedAt", GENERATED_AT.minusSeconds(50).atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    @Test
    @DisplayName("RUNNING execution 재개 조회는 work unit 실제 count와 unfinished unit을 복원한다")
    void restoresResumableExecutionProgress() {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'COMPLETED', cutoff_reached = true, completed_at = :completedAt
                    WHERE work_unit_id = :workUnitId
                    """)
                .param("completedAt", GENERATED_AT.minusSeconds(5).atOffset(ZoneOffset.UTC))
                .param("workUnitId", workUnitId(1))
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'TRUNCATED', cutoff_reached = false, completed_at = :completedAt
                    WHERE work_unit_id = :workUnitId
                    """)
                .param("completedAt", GENERATED_AT.minusSeconds(4).atOffset(ZoneOffset.UTC))
                .param("workUnitId", workUnitId(2))
                .update();
        jdbcClient.sql("""
                    UPDATE market_news_collection_execution
                    SET call_count = 3999,
                        completed_work_unit_count = 0,
                        truncated_work_unit_count = 0
                    WHERE execution_id = :executionId
                    """).param("executionId", EXECUTION_ID).update();
        collectionRepository.recordWorkUnitPageProgress(workUnitId(3), 101, 2, 4, GENERATED_AT.minusSeconds(60));

        var resumable =
                collectionRepository.findResumableExecution("NEWS-INTEGRATION").orElseThrow();

        assertThat(resumable.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(resumable.consumedCallCount()).isEqualTo(3999);
        assertThat(resumable.plannedWorkUnitCount()).isEqualTo(6);
        assertThat(resumable.completedWorkUnitCount()).isEqualTo(1);
        assertThat(resumable.truncatedWorkUnitCount()).isEqualTo(1);
        assertThat(resumable.workUnits()).extracting(unit -> unit.order()).containsExactly(3, 4, 5, 6);
        assertThat(resumable.workUnits().getFirst().nextProviderStart()).isEqualTo(201);
        assertThat(resumable.workUnits().getFirst().collectedCallCount()).isEqualTo(2);
        assertThat(resumable.workUnits().getFirst().collectedRawItemCount()).isEqualTo(4);
        assertThat(resumable.workUnits().getFirst().oldestProvidedAt()).isEqualTo(GENERATED_AT.minusSeconds(60));
    }

    @Test
    @DisplayName("terminal page 완료는 cursor와 cutoff 상태를 한 row update로 보존한다")
    void completesTerminalPageWithCursorAndState() {
        record TerminalPageRow(
                int providerStart,
                int callCount,
                int rawItemCount,
                Instant oldestProvidedAt,
                String state,
                boolean cutoffReached) {}

        collectionRepository.completeWorkUnitPage(
                workUnitId(3), 901, 10, 9, GENERATED_AT.minusSeconds(60), GENERATED_AT);

        TerminalPageRow terminal = jdbcClient
                .sql("""
                    SELECT last_provider_start, call_count, raw_item_count,
                           oldest_provided_at, state, cutoff_reached
                    FROM market_news_collection_work_unit
                    WHERE work_unit_id = :workUnitId
                    """)
                .param("workUnitId", workUnitId(3))
                .query((rs, rowNum) -> new TerminalPageRow(
                        rs.getInt("last_provider_start"),
                        rs.getInt("call_count"),
                        rs.getInt("raw_item_count"),
                        rs.getObject("oldest_provided_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        rs.getString("state"),
                        rs.getBoolean("cutoff_reached")))
                .single();
        var resumable =
                collectionRepository.findResumableExecution("NEWS-INTEGRATION").orElseThrow();

        assertThat(terminal)
                .isEqualTo(new TerminalPageRow(901, 10, 9, GENERATED_AT.minusSeconds(60), "COMPLETED", true));
        assertThat(resumable.workUnits()).extracting(unit -> unit.order()).containsExactly(1, 2, 4, 5, 6);
    }

    @Test
    @DisplayName("같은 provider 위치의 변경된 payload는 기존 raw evidence와 일치하지 않는다")
    void rejectsChangedPayloadAtExistingProviderPosition() {
        NewsProviderItem original = new NewsProviderItem(
                "아파트 거래 가격",
                "https://news.example.test/original",
                null,
                "서울 아파트 매매 거래",
                "Fri, 24 Jul 2026 18:00:00 +0900",
                1,
                1);
        NewsProviderItem changed = new NewsProviderItem(
                "아파트 공급 정책",
                "https://news.example.test/changed",
                null,
                "서울 아파트 공급",
                "Fri, 24 Jul 2026 18:01:00 +0900",
                1,
                1);

        collectionRepository.saveRawItems(workUnitId(1), List.of(original), GENERATED_AT);

        assertThat(collectionRepository.rawItemMatches(workUnitId(1), original)).isTrue();
        assertThat(collectionRepository.rawItemMatches(workUnitId(1), changed)).isFalse();
    }

    @Test
    @DisplayName("재개 시 budget이 이미 소진되면 RUNNING unit도 SKIPPED_BUDGET으로 종료한다")
    void closesRunningUnitsWhenResumedBudgetIsAlreadyExhausted() {
        collectionRepository.markRemainingSkippedBudget(EXECUTION_ID, GENERATED_AT);

        Integer skipped = jdbcClient
                .sql("""
                    SELECT count(*)
                    FROM market_news_collection_work_unit
                    WHERE execution_id = :executionId
                      AND state = 'SKIPPED_BUDGET'
                      AND completed_at = :completedAt
                    """)
                .param("executionId", EXECUTION_ID)
                .param("completedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .single();

        assertThat(skipped).isEqualTo(6);
    }

    @Test
    @DisplayName("V20 저장 구조는 raw-first 근거를 보존하고 전국 snapshot을 원자 발행한다")
    void storesRawFirstAndPublishesNationwideSnapshot() {
        NewsProviderItem raw = new NewsProviderItem(
                "<b>아파트</b> 거래 가격",
                "https://news.example.com/article/1",
                "https://n.news.naver.com/article/1",
                "서울 아파트 매매 거래 기사",
                "Fri, 24 Jul 2026 18:00:00 +0900",
                1,
                1);
        collectionRepository.saveRawItems(workUnitId(1), List.of(raw), GENERATED_AT);

        long articleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "아파트 거래 가격",
                        "서울 아파트 매매 거래 기사",
                        "https://news.example.com/article/1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        Instant.parse("2026-07-24T09:00:00Z"),
                        1,
                        1),
                GENERATED_AT);
        collectionRepository.linkRawItem(workUnitId(1), raw, articleId);
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V2",
                MarketNewsCategory.TRANSACTION_PRICE,
                new MarketNewsRelationMatch(MarketNewsRelationType.NATIONWIDE, null, null, List.of("아파트", "거래")));
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V2",
                MarketNewsCategory.TRANSACTION_PRICE,
                new MarketNewsRelationMatch(MarketNewsRelationType.SAME_SIGUNGU, "11680", null, List.of("서울", "강남구")));

        for (int order = 1; order <= 6; order++) {
            collectionRepository.finishWorkUnit(
                    workUnitId(order),
                    MarketNewsWorkUnitState.COMPLETED,
                    order == 1 ? 1 : 0,
                    order == 1 ? 1 : 0,
                    order == 1 ? Instant.parse("2026-07-24T09:00:00Z") : null,
                    true,
                    null,
                    GENERATED_AT.minusSeconds(10 - order));
        }
        collectionRepository.finishExecution(EXECUTION_ID, MarketNewsExecutionState.COMPLETED, null, GENERATED_AT);

        var published = collectionRepository.publishEligibleScopes(EXECUTION_ID, GENERATED_AT);
        var readRepository = new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC));
        var result = readRepository
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20)
                .orElseThrow();

        assertThat(published).hasSize(1);
        assertThat(jdbcClient
                        .sql("""
                            SELECT completed_work_unit_count, truncated_work_unit_count,
                                   raw_item_count, article_count, relation_count
                            FROM market_news_collection_execution
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query((rs, rowNum) -> java.util.Arrays.asList(
                                rs.getInt("completed_work_unit_count"),
                                rs.getInt("truncated_work_unit_count"),
                                rs.getInt("raw_item_count"),
                                rs.getInt("article_count"),
                                rs.getInt("relation_count")))
                        .single())
                .containsExactly(6, 0, 1, 1, 2);
        assertThat(result.snapshotId()).isEqualTo(published.getFirst().snapshotId());
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.articleId()).isEqualTo(articleId);
            assertThat(item.title()).isEqualTo("아파트 거래 가격");
            assertThat(item.url()).isEqualTo("https://news.example.com/article/1");
        });
        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*) FROM market_news_raw_item
                            WHERE article_id = :articleId AND rejection_reason IS NULL
                            """)
                        .param("articleId", articleId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                            SELECT has_table_privilege(
                                'home_search_property_runtime',
                                'public.market_news_snapshot',
                                'SELECT'
                            )
                            """).query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient
                        .sql("""
                            SELECT event_type, topic_name, aggregate_type, aggregate_id,
                                   aggregate_version, correlation_id,
                                   payload ->> 'snapshotId',
                                   payload ->> 'scopeType',
                                   payload ->> 'regionCode',
                                   payload ->> 'dataCutoff'
                            FROM event_outbox
                            WHERE event_type = 'NewsSnapshotPublished'
                              AND aggregate_id = :snapshotId
                            """)
                        .param("snapshotId", result.snapshotId().toString())
                        .query((rs, rowNum) -> java.util.Arrays.asList(
                                rs.getString("event_type"),
                                rs.getString("topic_name"),
                                rs.getString("aggregate_type"),
                                rs.getString("aggregate_id"),
                                rs.getLong("aggregate_version"),
                                rs.getString("correlation_id"),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getString(9),
                                java.time.OffsetDateTime.parse(rs.getString(10)).toInstant()))
                        .single())
                .containsExactly(
                        "NewsSnapshotPublished",
                        "property.insight-events.v1",
                        "NewsSnapshot",
                        result.snapshotId().toString(),
                        1L,
                        EXECUTION_ID.toString(),
                        result.snapshotId().toString(),
                        "NATIONWIDE",
                        null,
                        result.dataCutoff());

        UUID nextExecutionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174300");
        Instant nextGeneratedAt = GENERATED_AT.plusSeconds(4 * 60 * 60);
        insertCompletedNationwideExecution(nextExecutionId, nextGeneratedAt);

        collectionRepository.publishEligibleScopes(nextExecutionId, nextGeneratedAt);
        var nextResult = new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(nextGeneratedAt, ZoneOffset.UTC))
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20)
                .orElseThrow();

        assertThat(nextResult.items())
                .as("incremental publication은 30일 범위의 직전 정상 기사도 유지해야 한다")
                .extracting(item -> item.articleId())
                .containsExactly(articleId);

        new JdbcMarketNewsQualityRepository(jdbcClient)
                .withdrawPublished(
                        nextResult.snapshotId(), MarketNewsWithdrawalReason.RELEVANCE_PRECISION_BELOW_THRESHOLD)
                .orElseThrow();
        UUID recoveryExecutionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174301");
        Instant recoveryGeneratedAt = nextGeneratedAt.plusSeconds(4 * 60 * 60);
        insertCompletedNationwideExecution(recoveryExecutionId, recoveryGeneratedAt);

        collectionRepository.publishEligibleScopes(recoveryExecutionId, recoveryGeneratedAt);
        var recovered = new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(recoveryGeneratedAt, ZoneOffset.UTC))
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20)
                .orElseThrow();

        assertThat(recovered.items())
                .as("회수된 current는 합치지 않고 같은 policy의 직전 last-good을 복구해야 한다")
                .extracting(item -> item.articleId())
                .containsExactly(articleId);
    }

    @Test
    @DisplayName("cursor는 새 publication 이후에도 최초 snapshot의 다음 순위를 이어서 읽는다")
    void cursorRemainsBoundToSupersededSnapshot() {
        UUID originalSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174710");
        UUID replacementSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174711");
        long[] articleIds = new long[3];

        for (int order = 1; order <= 3; order++) {
            articleIds[order - 1] = collectionRepository.upsertArticle(
                    new NormalizedNewsItem(
                            "아파트 거래 기사 " + order,
                            "서울 아파트 매매 거래",
                            "https://news.example.test/cursor/" + order,
                            String.format("%064x", order),
                            GENERATED_AT.minusSeconds(order * 60L),
                            1,
                            order),
                    GENERATED_AT);
            collectionRepository.saveRelation(
                    articleIds[order - 1],
                    "NEWS_V2",
                    MarketNewsCategory.TRANSACTION_PRICE,
                    new MarketNewsRelationMatch(MarketNewsRelationType.NATIONWIDE, null, null, List.of("아파트", "거래")));
        }

        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V2', 'NATIONWIDE',
                        'PUBLISHED', :generatedAt, :dataCutoff, 3
                    )
                    """)
                .param("snapshotId", originalSnapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        for (int order = 1; order <= 3; order++) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_snapshot_item (
                            snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                        )
                        SELECT :snapshotId, relation.article_id, relation.relation_id,
                               relation.category, :sortRank, :providerRank
                        FROM market_news_relation relation
                        WHERE relation.article_id = :articleId
                          AND relation.policy_version = 'NEWS_V2'
                        """)
                    .param("snapshotId", originalSnapshotId)
                    .param("articleId", articleIds[order - 1])
                    .param("sortRank", order)
                    .param("providerRank", order)
                    .update();
        }

        JdbcMarketNewsReadRepository repository =
                new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC));
        var firstPage = repository
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 2)
                .orElseThrow();
        MarketNewsCursor cursor = MarketNewsCursor.decode(firstPage.nextCursor());

        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V2', 'NATIONWIDE',
                        'BUILDING', :generatedAt, :dataCutoff, 0
                    )
                    """)
                .param("snapshotId", replacementSnapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'SUPERSEDED', superseded_by_snapshot_id = :replacementSnapshotId
                    WHERE snapshot_id = :originalSnapshotId
                    """)
                .param("replacementSnapshotId", replacementSnapshotId)
                .param("originalSnapshotId", originalSnapshotId)
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'PUBLISHED'
                    WHERE snapshot_id = :replacementSnapshotId
                    """)
                .param("replacementSnapshotId", replacementSnapshotId)
                .update();

        var secondPage = repository
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, cursor, 2)
                .orElseThrow();

        assertThat(firstPage.snapshotId()).isEqualTo(originalSnapshotId);
        assertThat(firstPage.items())
                .extracting(item -> item.articleId())
                .containsExactly(articleIds[0], articleIds[1]);
        assertThat(secondPage.snapshotId()).isEqualTo(originalSnapshotId);
        assertThat(secondPage.items()).extracting(item -> item.articleId()).containsExactly(articleIds[2]);
        assertThat(secondPage.nextCursor()).isNull();

        JdbcMarketNewsQualityRepository qualityRepository = new JdbcMarketNewsQualityRepository(jdbcClient);
        var withdrawn = qualityRepository
                .withdrawPublished(replacementSnapshotId, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD)
                .orElseThrow();
        assertThat(withdrawn.lastGood()).isNotNull();
        assertThat(withdrawn.lastGood().snapshotId()).isEqualTo(originalSnapshotId);
        assertThat(qualityRepository.withdrawPublished(
                        replacementSnapshotId, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD))
                .as("동일 사유 재시도는 idempotent해야 한다")
                .isPresent();

        var lastGood = repository
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 2)
                .orElseThrow();
        assertThat(lastGood.snapshotId()).isEqualTo(originalSnapshotId);
        assertThat(lastGood.dataStatus()).isEqualTo(MarketNewsDataStatus.STALE);
    }

    @Test
    @DisplayName("자동 hard gate는 미래 제공 시각이 포함된 snapshot을 발행하지 않고 REJECTED로 보존한다")
    void rejectsSnapshotThatFailsAutomaticHardGate() {
        NewsProviderItem raw = new NewsProviderItem(
                "아파트 거래 가격",
                "https://news.example.test/future/1",
                null,
                "서울 아파트 매매 거래",
                "Fri, 24 Jul 2026 18:31:00 +0900",
                1,
                1);
        collectionRepository.saveRawItems(workUnitId(1), List.of(raw), GENERATED_AT);
        long articleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "아파트 거래 가격",
                        "서울 아파트 매매 거래",
                        "https://news.example.test/future/1",
                        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        GENERATED_AT.plusSeconds(60),
                        1,
                        1),
                GENERATED_AT);
        collectionRepository.linkRawItem(workUnitId(1), raw, articleId);
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V2",
                MarketNewsCategory.TRANSACTION_PRICE,
                new MarketNewsRelationMatch(MarketNewsRelationType.NATIONWIDE, null, null, List.of("아파트", "거래")));
        for (int order = 1; order <= 6; order++) {
            collectionRepository.finishWorkUnit(
                    workUnitId(order),
                    MarketNewsWorkUnitState.COMPLETED,
                    order == 1 ? 1 : 0,
                    order == 1 ? 1 : 0,
                    order == 1 ? GENERATED_AT.plusSeconds(60) : null,
                    true,
                    null,
                    GENERATED_AT.minusSeconds(10 - order));
        }
        collectionRepository.finishExecution(EXECUTION_ID, MarketNewsExecutionState.COMPLETED, null, GENERATED_AT);

        assertThat(collectionRepository.publishEligibleScopes(EXECUTION_ID, GENERATED_AT))
                .isEmpty();
        assertThat(jdbcClient
                        .sql("""
                            SELECT build_status
                            FROM market_news_snapshot
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", EXECUTION_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("REJECTED");
        assertThat(new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC))
                        .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20))
                .isEmpty();
    }

    @Test
    @DisplayName("30일 BOOTSTRAP은 모든 전국 unit이 TRUNCATED여도 수집된 기사만 원자 발행한다")
    void publishesCollectedItemsFromTruncatedBootstrap() {
        jdbcClient.sql("""
                    UPDATE market_news_collection_execution
                    SET execution_type = 'BOOTSTRAP', request_id = 'BOOTSTRAP:123e4567-e89b-12d3-a456-426614174209'
                    WHERE execution_id = :executionId
                    """).param("executionId", EXECUTION_ID).update();
        NewsProviderItem raw = new NewsProviderItem(
                "아파트 공급 분양",
                "https://news.example.test/bootstrap/1",
                null,
                "서울 아파트 공급 기사",
                "Fri, 24 Jul 2026 18:00:00 +0900",
                1,
                1);
        collectionRepository.saveRawItems(workUnitId(1), List.of(raw), GENERATED_AT);
        long articleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "아파트 공급 분양",
                        "서울 아파트 공급 기사",
                        "https://news.example.test/bootstrap/1",
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        GENERATED_AT.minusSeconds(600),
                        1,
                        1),
                GENERATED_AT);
        collectionRepository.linkRawItem(workUnitId(1), raw, articleId);
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V2",
                MarketNewsCategory.SUPPLY_SALE,
                new MarketNewsRelationMatch(MarketNewsRelationType.NATIONWIDE, null, null, List.of("아파트", "공급")));

        for (int order = 1; order <= 6; order++) {
            collectionRepository.finishWorkUnit(
                    workUnitId(order),
                    MarketNewsWorkUnitState.TRUNCATED,
                    order == 1 ? 10 : 0,
                    order == 1 ? 1 : 0,
                    order == 1 ? GENERATED_AT.minusSeconds(600) : null,
                    false,
                    MarketNewsFailureKind.CUTOFF_NOT_REACHED,
                    GENERATED_AT.minusSeconds(10 - order));
        }
        collectionRepository.finishExecution(EXECUTION_ID, MarketNewsExecutionState.PARTIAL, null, GENERATED_AT);

        var published = collectionRepository.publishEligibleScopes(EXECUTION_ID, GENERATED_AT);
        var result = new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC))
                .findPublished(MarketNewsScopeType.NATIONWIDE, null, MarketNewsCategory.ALL, null, 20)
                .orElseThrow();

        assertThat(published).singleElement();
        assertThat(result.items())
                .singleElement()
                .satisfies(item -> assertThat(item.articleId()).isEqualTo(articleId));
    }

    @Test
    @DisplayName("NAVER 호출 budget은 execution별이 아니라 KST 날짜 전체에서 설정값을 넘지 않는다")
    void enforcesDailyCallBudgetAcrossExecutions() {
        jdbcClient.sql("""
                    UPDATE market_news_collection_execution
                    SET call_count = 4
                    WHERE execution_id = :executionId
                    """).param("executionId", EXECUTION_ID).update();
        UUID secondExecutionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174400");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget, call_count,
                        planned_work_unit_count, started_at
                    ) VALUES (
                        :executionId, 'NEWS-BUDGET-SECOND', 'MAJOR_COMPLEX', 'NEWS_V1',
                        :scheduledAt, :cutoff, 'RUNNING', 5, 0, 0, :startedAt
                    )
                    """)
                .param("executionId", secondExecutionId)
                .param("scheduledAt", GENERATED_AT.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("cutoff", GENERATED_AT.minusSeconds(7200).atOffset(ZoneOffset.UTC))
                .param("startedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .update();

        collectionRepository.incrementExecutionCallCount(secondExecutionId);

        assertThatThrownBy(() -> collectionRepository.incrementExecutionCallCount(secondExecutionId))
                .isInstanceOf(NewsCallBudgetExceededException.class);
        assertThat(jdbcClient
                        .sql("""
                            SELECT sum(call_count)
                            FROM market_news_collection_execution
                            WHERE (scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                                  = (:scheduledAt AT TIME ZONE 'Asia/Seoul')::date
                            """)
                        .param("scheduledAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                        .query(Long.class)
                        .single())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("retention은 raw 7일, normalized 30일, quality 180일 경계를 FK 안전 순서로 적용한다")
    void appliesRetentionBoundariesWithoutDeletingPublishedPointer() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        long articleId = jdbcClient
                .sql("""
                    INSERT INTO market_news_article (
                        provider, canonical_url_hash, public_url, title, provided_at,
                        first_seen_at, last_seen_at
                    ) VALUES (
                        'NAVER', repeat('b', 64), 'https://news.example.test/expired',
                        '보관 기한이 지난 아파트 거래 기사', :providedAt, :seenAt, :seenAt
                    )
                    RETURNING article_id
                    """)
                .param("providedAt", now.minusSeconds(31L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .param("seenAt", now.minusSeconds(31L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long relationId = jdbcClient
                .sql("""
                    INSERT INTO market_news_relation (
                        article_id, policy_version, category, relation_type, created_at
                    ) VALUES (
                        :articleId, 'NEWS_V1', 'TRANSACTION_PRICE', 'NATIONWIDE', :createdAt
                    )
                    RETURNING relation_id
                    """)
                .param("articleId", articleId)
                .param("createdAt", now.minusSeconds(31L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        UUID snapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174500");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V1', 'NATIONWIDE',
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", now.minusSeconds(31L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .param("dataCutoff", now.minusSeconds(31L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'TRANSACTION_PRICE', 1, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_raw_item (
                        work_unit_id, provider_start, provider_rank, title_raw,
                        received_at, article_id
                    ) VALUES (
                        :workUnitId, 1, 1, 'expired raw',
                        :receivedAt, :articleId
                    )
                    """)
                .param("workUnitId", workUnitId(1))
                .param("receivedAt", now.minusSeconds(8L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .param("articleId", articleId)
                .update();
        UUID reviewSetId = UUID.fromString("123e4567-e89b-12d3-a456-426614174501");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_quality_review_set (
                        review_set_id, policy_version, status, sampled_at,
                        total_sample_count, minimum_category_count, covered_sido_count,
                        direct_complex_count, same_dong_count, same_sigungu_count,
                        complex_challenge_count, url_sample_count
                    ) VALUES (
                        :reviewSetId, 'NEWS_V1', 'INSUFFICIENT_SAMPLE', :sampledAt,
                        1, 0, 0, 0, 0, 0, 0, 0
                    )
                    """)
                .param("reviewSetId", reviewSetId)
                .param("sampledAt", now.minusSeconds(181L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_quality_label (
                        review_set_id, article_id, relation_id, sample_stratum, sampled_at
                    ) VALUES (
                        :reviewSetId, :articleId, :relationId, 'RETENTION_TEST', :sampledAt
                    )
                    """)
                .param("reviewSetId", reviewSetId)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .param("sampledAt", now.minusSeconds(181L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .update();

        new JdbcMarketNewsRetentionRepository(jdbcClient).deleteExpired(now);

        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_news_raw_item WHERE article_id = :articleId")
                        .param("articleId", articleId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_news_article WHERE article_id = :articleId")
                        .param("articleId", articleId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbcClient
                        .sql("SELECT item_count FROM market_news_snapshot WHERE snapshot_id = :snapshotId")
                        .param("snapshotId", snapshotId)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_news_quality_label WHERE review_set_id = :reviewSetId")
                        .param("reviewSetId", reviewSetId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("retention은 180일 지난 execution 보정 근거를 먼저 지우고 execution을 정리한다")
    void deletesExpiredExecutionCorrectionEvidenceBeforeExecution() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        UUID expiredExecutionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174533");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, state, call_budget, planned_work_unit_count,
                        started_at, completed_at, failure_kind
                    ) VALUES (
                        :executionId, 'NEWS-EXPIRED-CORRECTION', 'MAJOR_COMPLEX', 'NEWS_V3',
                        :scheduledAt, 'FAILED', 4000, 0,
                        :startedAt, :completedAt, 'DAILY_QUOTA'
                    )
                    """)
                .param("executionId", expiredExecutionId)
                .param("scheduledAt", now.minusSeconds(181L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .param("startedAt", now.minusSeconds(181L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .param("completedAt", now.minusSeconds(181L * 24 * 60 * 60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient.sql("""
                    INSERT INTO market_news_execution_aggregate_correction (
                        execution_id, correction_reason,
                        old_completed_count, new_completed_count,
                        old_truncated_count, new_truncated_count,
                        old_failed_count, new_failed_count,
                        old_skipped_budget_count, new_skipped_budget_count,
                        old_raw_item_count, new_raw_item_count
                    ) VALUES (
                        :executionId, 'DERIVED_WORK_UNIT_RECONCILIATION',
                        1, 0, 0, 0, 0, 0, 0, 0, 1, 0
                    )
                    """).param("executionId", expiredExecutionId).update();
        jdbcClient.sql("""
                    INSERT INTO market_news_execution_failure_correction (
                        execution_id, correction_reason, old_failure_kind, new_failure_kind
                    ) VALUES (
                        :executionId, 'PROVIDER_FAILURE_PRECEDENCE',
                        'DAILY_CALL_BUDGET', 'DAILY_QUOTA'
                    )
                    """).param("executionId", expiredExecutionId).update();

        new JdbcMarketNewsRetentionRepository(jdbcClient).deleteExpired(now);

        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*)
                            FROM market_news_collection_execution
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", expiredExecutionId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbcClient
                        .sql("""
                            SELECT
                                (SELECT count(*) FROM market_news_execution_aggregate_correction
                                 WHERE execution_id = :executionId)
                              + (SELECT count(*) FROM market_news_execution_failure_correction
                                 WHERE execution_id = :executionId)
                            """)
                        .param("executionId", expiredExecutionId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("NEWS_V5 general plan은 6개 전국 query와 root SIDO별 보충 query를 raw work unit으로 고정한다")
    void plansVersionedGeneralQueriesAndComplexCorpus() {
        Instant scheduledAt = GENERATED_AT.plusSeconds(60);

        var execution = collectionRepository.planGeneral(
                "123e4567-e89b-12d3-a456-426614174600", scheduledAt, scheduledAt.minusSeconds(7200), 4000);

        assertThat(execution.policyVersion()).isEqualTo("NEWS_V5");
        assertThat(execution.workUnits()).hasSize(8);
        assertThat(execution.workUnits().stream().filter(unit -> unit.scopeType() == MarketNewsScopeType.NATIONWIDE))
                .hasSize(6);
        assertThat(execution.workUnits().stream().filter(unit -> unit.scopeType() == MarketNewsScopeType.SIDO))
                .hasSize(2)
                .extracting(unit -> unit.query())
                .containsExactly("Seoul 아파트 부동산", "Seoul 주택 분양");
        assertThat(execution.workUnits().stream().filter(unit -> unit.scopeType() == MarketNewsScopeType.SIDO))
                .allSatisfy(unit -> {
                    assertThat(unit.regionCode()).isEqualTo("11");
                    assertThat(unit.matchingCorpus())
                            .extracting(complex -> complex.complexId())
                            .containsExactly(501L);
                });
        assertThat(jdbcClient
                        .sql("""
                            SELECT count(*)
                            FROM market_news_collection_work_unit
                            WHERE execution_id = :executionId
                            """)
                        .param("executionId", execution.executionId())
                        .query(Long.class)
                        .single())
                .isEqualTo(8L);
    }

    @Test
    @DisplayName("SIDO 보충 query가 모두 끝나기 전에는 해당 시도 snapshot을 발행하지 않는다")
    void publishesSidoOnlyAfterAllSupplementalQueriesComplete() {
        Instant scheduledAt = GENERATED_AT.plusSeconds(90);
        var execution = collectionRepository.planGeneral(
                "123e4567-e89b-12d3-a456-426614174604", scheduledAt, scheduledAt.minusSeconds(7200), 4000);
        var sidoUnits = execution.workUnits().stream()
                .filter(unit -> unit.scopeType() == MarketNewsScopeType.SIDO)
                .toList();

        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'COMPLETED', completed_at = :completedAt, cutoff_reached = true
                    WHERE execution_id = :executionId
                      AND scope_kind = 'NATIONAL_CATEGORY'
                    """)
                .param("completedAt", scheduledAt.atOffset(ZoneOffset.UTC))
                .param("executionId", execution.executionId())
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'COMPLETED', completed_at = :completedAt, cutoff_reached = true
                    WHERE work_unit_id = :workUnitId
                    """)
                .param("completedAt", scheduledAt.atOffset(ZoneOffset.UTC))
                .param("workUnitId", sidoUnits.getFirst().workUnitId())
                .update();

        assertThat(collectionRepository.publishEligibleScopes(execution.executionId(), scheduledAt).stream()
                        .filter(snapshot -> snapshot.scopeType() == MarketNewsScopeType.SIDO))
                .isEmpty();

        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'COMPLETED', completed_at = :completedAt, cutoff_reached = true
                    WHERE work_unit_id = :workUnitId
                    """)
                .param("completedAt", scheduledAt.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("workUnitId", sidoUnits.getLast().workUnitId())
                .update();

        assertThat(
                        collectionRepository
                                .publishEligibleScopes(execution.executionId(), scheduledAt.plusSeconds(1))
                                .stream()
                                .filter(snapshot -> snapshot.scopeType() == MarketNewsScopeType.SIDO))
                .singleElement()
                .satisfies(snapshot -> assertThat(snapshot.regionCode()).isEqualTo("11"));
    }

    @Test
    @DisplayName("주요 단지 후보 reader와 publisher는 90일 정상 거래 근거와 주간 BUILDING 상태를 저장한다")
    void readsAndStagesMajorComplexSelection() {
        JdbcMajorNewsComplexSelectionRepository repository = new JdbcMajorNewsComplexSelectionRepository(jdbcClient);

        var candidates = repository.findCandidates(java.time.LocalDate.parse("2025-12-31"));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.complexId()).isEqualTo(501L);
            assertThat(candidate.tradeCount90d()).isEqualTo(2);
            assertThat(candidate.sidoCode()).isEqualTo("11");
        });
        repository.publish(java.time.LocalDate.parse("2025-12-29"), candidates);
        assertThat(repository.hasPublishedSelection(java.time.LocalDate.parse("2025-12-29")))
                .isFalse();
        assertThat(jdbcClient.sql("""
                            SELECT selection_status
                            FROM market_news_major_complex_selection
                            WHERE selection_week = DATE '2025-12-29'
                            """).query(String.class).single()).isEqualTo("BUILDING");
        assertThatThrownBy(() -> new MajorNewsComplexSelectionService(repository)
                        .select(java.time.LocalDate.parse("2025-12-31")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("17개 시도");
    }

    @Test
    @DisplayName("시군구 계층이 없는 세종 단지도 시도명을 행정 도시 근거로 사용해 후보에 포함한다")
    void includesSejongCandidateWithoutSigunguLevel() {
        seedSejongNewsCandidate();
        JdbcMajorNewsComplexSelectionRepository repository = new JdbcMajorNewsComplexSelectionRepository(jdbcClient);

        var candidates = repository.findCandidates(java.time.LocalDate.parse("2025-12-31"));

        assertThat(candidates)
                .filteredOn(candidate -> candidate.complexId() == 502L)
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.sidoCode()).isEqualTo("36");
                    assertThat(candidate.sidoName()).isEqualTo("Sejong");
                    assertThat(candidate.sigunguName()).isEqualTo("Sejong");
                    assertThat(candidate.dongName()).isEqualTo("Sejong-dong");
                });
    }

    @Test
    @DisplayName("세종 외 시도의 시군구 누락 계층은 주요 단지 후보로 보정하지 않는다")
    void doesNotMaskMissingSigunguOutsideSejong() {
        seedUnexpectedDirectAdminNewsCandidate();
        JdbcMajorNewsComplexSelectionRepository repository = new JdbcMajorNewsComplexSelectionRepository(jdbcClient);

        var candidates = repository.findCandidates(java.time.LocalDate.parse("2025-12-31"));

        assertThat(candidates).noneMatch(candidate -> candidate.complexId() == 503L);
    }

    @Test
    @DisplayName("세종 SIDO query corpus는 시도 code와 이름을 시군구 근거로 재사용한다")
    void plansSejongCorpusWithoutSigunguLevel() {
        seedSejongNewsCandidate();
        Instant scheduledAt = GENERATED_AT.plusSeconds(120);

        var execution = collectionRepository.planGeneral(
                "123e4567-e89b-12d3-a456-426614174602", scheduledAt, scheduledAt.minusSeconds(7200), 4000);

        assertThat(execution.workUnits())
                .filteredOn(unit -> "36".equals(unit.regionCode()))
                .hasSize(2)
                .allSatisfy(unit -> assertThat(unit.matchingCorpus())
                        .filteredOn(complex -> complex.complexId() == 502L)
                        .singleElement()
                        .satisfies(complex -> {
                            assertThat(complex.region().sidoCode()).isEqualTo("36");
                            assertThat(complex.region().sigunguCode()).isEqualTo("36");
                            assertThat(complex.region().sigunguName()).isEqualTo("Sejong");
                            assertThat(complex.region().dongCode()).isEqualTo("36110103");
                        }));
    }

    @Test
    @DisplayName("세종 단지 상세은 시도 code로 저장된 SAME_SIGUNGU 뉴스를 지역 기사로 반환한다")
    void readsSejongSameSigunguNewsWithoutSigunguLevel() {
        seedSejongNewsCandidate();
        long articleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "Sejong 아파트 거래 가격",
                        "Sejong-dong 주택 매매 거래",
                        "https://news.example.test/sejong/502",
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        GENERATED_AT.minusSeconds(600),
                        1,
                        1),
                GENERATED_AT);
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V1",
                MarketNewsCategory.TRANSACTION_PRICE,
                new MarketNewsRelationMatch(MarketNewsRelationType.SAME_SIGUNGU, "36", null, List.of("Sejong")));
        long relationId = jdbcClient
                .sql("""
                    SELECT relation_id
                    FROM market_news_relation
                    WHERE article_id = :articleId
                    """)
                .param("articleId", articleId)
                .query(Long.class)
                .single();
        UUID snapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174603");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V1', 'SIDO', '36',
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'TRANSACTION_PRICE', 1, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .update();
        JdbcMarketNewsReadRepository repository =
                new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC));

        assertThat(repository.findComplexNews(502L, 5)).singleElement().satisfies(item -> {
            assertThat(item.articleId()).isEqualTo(articleId);
            assertThat(item.regionCode()).isEqualTo("36");
            assertThat(item.relationType()).isEqualTo(MarketNewsRelationType.SAME_SIGUNGU);
        });
    }

    @Test
    @DisplayName("단지 뉴스 reader는 published DIRECT_COMPLEX relation만 고정 limit 순서로 반환한다")
    void readsPublishedComplexNews() {
        long articleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "Sample Apartment 거래 가격",
                        "Seoul Gangnam-gu 아파트 매매 거래",
                        "https://news.example.test/complex/501",
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        GENERATED_AT.minusSeconds(600),
                        1,
                        1),
                GENERATED_AT);
        collectionRepository.saveRelation(
                articleId,
                "NEWS_V1",
                MarketNewsCategory.TRANSACTION_PRICE,
                new MarketNewsRelationMatch(
                        MarketNewsRelationType.DIRECT_COMPLEX, "11", 501L, List.of("Sample Apartment")));
        long relationId = jdbcClient
                .sql("""
                    SELECT relation_id
                    FROM market_news_relation
                    WHERE article_id = :articleId
                    """)
                .param("articleId", articleId)
                .query(Long.class)
                .single();
        UUID snapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174601");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V1', 'SIDO', '11',
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'TRANSACTION_PRICE', 1, 1
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .update();
        JdbcMarketNewsReadRepository repository =
                new JdbcMarketNewsReadRepository(jdbcClient, Clock.fixed(GENERATED_AT, ZoneOffset.UTC));

        assertThat(repository.existsRootSidoCode("11")).isTrue();
        assertThat(repository.existsRootSidoCode("99")).isFalse();
        assertThat(repository.existsComplex(501L)).isTrue();
        assertThat(repository.findComplexNews(501L, 5)).singleElement().satisfies(item -> {
            assertThat(item.articleId()).isEqualTo(articleId);
            assertThat(item.relationType()).isEqualTo(MarketNewsRelationType.DIRECT_COMPLEX);
        });

        jdbcClient.sql("""
                    INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                    VALUES (1004, 111, '1168010300101410001', 'Gangnam model house address', 37.5124, 127.0457)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex (
                        id, parcel_id, region_id, complex_pk, apt_seq, name, unit_cnt
                    ) VALUES (
                        504, 1004, 111, 'COMPLEX-PK-504', 'APT-504', 'Gangnam', 300
                    )
                    """).update();
        long geographicNameArticleId = collectionRepository.upsertArticle(
                new NormalizedNewsItem(
                        "Different Apartment subscription",
                        "Gangnam project model house is at Seoul Gangnam-gu Sample-dong",
                        "https://news.example.test/complex/geographic-name",
                        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        GENERATED_AT.minusSeconds(300),
                        1,
                        2),
                GENERATED_AT);
        collectionRepository.saveRelation(
                geographicNameArticleId,
                "NEWS_V1",
                MarketNewsCategory.SUPPLY_SALE,
                new MarketNewsRelationMatch(
                        MarketNewsRelationType.DIRECT_COMPLEX,
                        "11",
                        504L,
                        List.of("Gangnam", "Gangnam-gu", "Sample-dong")));
        collectionRepository.saveRelation(
                geographicNameArticleId,
                "NEWS_V1",
                MarketNewsCategory.SUPPLY_SALE,
                new MarketNewsRelationMatch(
                        MarketNewsRelationType.SAME_DONG, "11680103", null, List.of("Gangnam-gu", "Sample-dong")));
        long geographicNameRelationId = jdbcClient
                .sql("""
                    SELECT relation_id
                    FROM market_news_relation
                    WHERE article_id = :articleId
                      AND relation_type = 'DIRECT_COMPLEX'
                    """)
                .param("articleId", geographicNameArticleId)
                .query(Long.class)
                .single();
        long geographicNameDongRelationId = jdbcClient
                .sql("""
                    SELECT relation_id
                    FROM market_news_relation
                    WHERE article_id = :articleId
                      AND relation_type = 'SAME_DONG'
                    """)
                .param("articleId", geographicNameArticleId)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'SUPPLY_SALE', 2, 2
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("articleId", geographicNameArticleId)
                .param("relationId", geographicNameRelationId)
                .update();
        UUID geographicFallbackSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174604");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V1', 'NATIONWIDE', NULL,
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", geographicFallbackSnapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'SUPPLY_SALE', 1, 2
                    )
                    """)
                .param("snapshotId", geographicFallbackSnapshotId)
                .param("articleId", geographicNameArticleId)
                .param("relationId", geographicNameDongRelationId)
                .update();

        assertThat(repository.findComplexNews(504L, 5))
                .as("억제한 지명 direct article은 같은 동 fallback으로도 우회 공개하지 않는다")
                .isEmpty();

        UUID replacementSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174602");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V1', 'SIDO', '11',
                        'BUILDING', :generatedAt, :dataCutoff, 0
                    )
                    """)
                .param("snapshotId", replacementSnapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", GENERATED_AT.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("dataCutoff", GENERATED_AT.atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'SUPERSEDED', superseded_by_snapshot_id = :replacementSnapshotId
                    WHERE snapshot_id = :snapshotId
                    """)
                .param("replacementSnapshotId", replacementSnapshotId)
                .param("snapshotId", snapshotId)
                .update();
        jdbcClient
                .sql("UPDATE market_news_snapshot SET build_status = 'PUBLISHED' WHERE snapshot_id = :snapshotId")
                .param("snapshotId", replacementSnapshotId)
                .update();
        new JdbcMarketNewsQualityRepository(jdbcClient)
                .withdrawPublished(replacementSnapshotId, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD)
                .orElseThrow();

        assertThat(repository.findComplexNews(501L, 5))
                .as("현재 snapshot 회수 후에도 직전 last-good 단지 뉴스를 유지한다")
                .anySatisfy(item -> {
                    assertThat(item.articleId()).isEqualTo(articleId);
                    assertThat(item.relationType()).isEqualTo(MarketNewsRelationType.DIRECT_COMPLEX);
                });
    }

    private void insertCompletedNationwideExecution(UUID executionId, Instant generatedAt) {
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget, call_count,
                        planned_work_unit_count, completed_work_unit_count,
                        started_at, completed_at
                    ) VALUES (
                        :executionId, :requestId, 'GENERAL', 'NEWS_V2',
                        :scheduledAt, :cutoff, 'COMPLETED', 4000, 0,
                        6, 6, :startedAt, :completedAt
                    )
                    """)
                .param("executionId", executionId)
                .param("requestId", "NEWS-INTEGRATION-NEXT:" + executionId)
                .param("scheduledAt", generatedAt.atOffset(ZoneOffset.UTC))
                .param("cutoff", generatedAt.minusSeconds(7200).atOffset(ZoneOffset.UTC))
                .param("startedAt", generatedAt.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("completedAt", generatedAt.atOffset(ZoneOffset.UTC))
                .update();
        for (int order = 1; order <= 6; order++) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_collection_work_unit (
                            work_unit_id, execution_id, unit_order, scope_kind, scope_type,
                            category, query_text, cutoff_at, cutoff_reached, state,
                            started_at, completed_at
                        ) VALUES (
                            :workUnitId, :executionId, :unitOrder, 'NATIONAL_CATEGORY',
                            'NATIONWIDE', :category, :queryText, :cutoff, true, 'COMPLETED',
                            :startedAt, :completedAt
                        )
                        """)
                    .param(
                            "workUnitId",
                            UUID.nameUUIDFromBytes((executionId + ":" + order).getBytes(StandardCharsets.UTF_8)))
                    .param("executionId", executionId)
                    .param("unitOrder", order)
                    .param("category", categories().get(order - 1).name())
                    .param("queryText", "next query " + order)
                    .param("cutoff", generatedAt.minusSeconds(7200).atOffset(ZoneOffset.UTC))
                    .param("startedAt", generatedAt.minusSeconds(50).atOffset(ZoneOffset.UTC))
                    .param("completedAt", generatedAt.minusSeconds(10).atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private void seedSejongNewsCandidate() {
        jdbcClient.sql("""
                    INSERT INTO region (id, code, name, region_type)
                    VALUES (2, '36', 'Sejong', 'si-do')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO region (id, parent_id, code, name, region_type)
                    VALUES (211, 2, '36110103', 'Sejong-dong', 'eup-myeon-dong')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                    VALUES (1002, 211, '3611010300101400001', 'Sejong sample address', 36.5, 127.25)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex (
                        id, parcel_id, region_id, complex_pk, apt_seq, name, unit_cnt
                    ) VALUES (
                        502, 1002, 211, 'COMPLEX-PK-502', 'APT-502', 'Sejong Apartment', 500
                    )
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO raw_trade_ingest (
                        id, source, source_key, lawd_cd, deal_ymd, page_no,
                        payload, payload_hash, status, processed_at
                    ) VALUES (
                        90003, 'RTMS', 'sejong-rtms-20251220', '36110', '202512', 1,
                        '{}', 'hash-3', 'NORMALIZED', now()
                    )
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO trade (
                        id, complex_id, deal_date, deal_amount, floor, excl_area,
                        source, source_key, complex_pk, apt_seq, raw_ingest_id
                    ) VALUES (
                        9003, 502, DATE '2025-12-20', 80000, 10, 84.90,
                        'RTMS', 'sejong-rtms-20251220', 'COMPLEX-PK-502', 'APT-502', 90003
                    )
                    """).update();
    }

    private void seedUnexpectedDirectAdminNewsCandidate() {
        jdbcClient.sql("""
                    INSERT INTO region (id, code, name, region_type)
                    VALUES (3, '99', 'Unexpected Sido', 'si-do')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO region (id, parent_id, code, name, region_type)
                    VALUES (311, 3, '99110101', 'Unexpected Dong', 'eup-myeon-dong')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
                    VALUES (1003, 311, '9911010100101400001', 'Unexpected sample address', 36.6, 127.35)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex (
                        id, parcel_id, region_id, complex_pk, apt_seq, name, unit_cnt
                    ) VALUES (
                        503, 1003, 311, 'COMPLEX-PK-503', 'APT-503', 'Unexpected Apartment', 300
                    )
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO raw_trade_ingest (
                        id, source, source_key, lawd_cd, deal_ymd, page_no,
                        payload, payload_hash, status, processed_at
                    ) VALUES (
                        90004, 'RTMS', 'unexpected-rtms-20251220', '99110', '202512', 1,
                        '{}', 'hash-4', 'NORMALIZED', now()
                    )
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO trade (
                        id, complex_id, deal_date, deal_amount, floor, excl_area,
                        source, source_key, complex_pk, apt_seq, raw_ingest_id
                    ) VALUES (
                        9004, 503, DATE '2025-12-20', 70000, 8, 84.90,
                        'RTMS', 'unexpected-rtms-20251220', 'COMPLEX-PK-503', 'APT-503', 90004
                    )
                    """).update();
    }

    private static UUID workUnitId(int order) {
        return UUID.fromString("123e4567-e89b-12d3-a456-42661417420" + order);
    }

    private static List<MarketNewsCategory> categories() {
        return List.of(
                MarketNewsCategory.POLICY,
                MarketNewsCategory.FINANCE_LOAN,
                MarketNewsCategory.SUPPLY_SALE,
                MarketNewsCategory.REDEVELOPMENT,
                MarketNewsCategory.TRANSACTION_PRICE,
                MarketNewsCategory.TRANSPORT_DEVELOPMENT);
    }
}
