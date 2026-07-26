package com.home.infrastructure.persistence.news;

import com.home.application.news.collection.MarketNewsCollectionExecution;
import com.home.application.news.collection.MarketNewsCollectionRepository;
import com.home.application.news.collection.MarketNewsCollectionResult;
import com.home.application.news.collection.MarketNewsQueryPolicyRegistry;
import com.home.application.news.collection.MarketNewsWorkUnitSpec;
import com.home.application.news.collection.NewsCallBudgetExceededException;
import com.home.application.news.collection.NewsProviderItem;
import com.home.application.news.collection.NormalizedNewsItem;
import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.application.news.collection.RawNewsPositionConflictException;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsExecutionState;
import com.home.domain.news.MarketNewsFailureKind;
import com.home.domain.news.MarketNewsRelationMatch;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWorkUnitKind;
import com.home.domain.news.MarketNewsWorkUnitState;
import com.home.domain.news.NewsComplexEvidence;
import com.home.domain.news.NewsRegionEvidence;
import com.home.domain.news.NewsRejectionReason;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMarketNewsCollectionRepository implements MarketNewsCollectionRepository {

    private final JdbcClient jdbcClient;
    private final MarketNewsQueryPolicyRegistry policyRegistry = new MarketNewsQueryPolicyRegistry();

    public JdbcMarketNewsCollectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<MarketNewsCollectionResult> findTerminalResult(String requestId) {
        return jdbcClient
                .sql("""
                    SELECT execution_id, state, call_count, completed_work_unit_count,
                           failed_work_unit_count, truncated_work_unit_count
                    FROM market_news_collection_execution
                    WHERE request_id = :requestId
                      AND state IN ('COMPLETED', 'PARTIAL', 'FAILED')
                    """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new MarketNewsCollectionResult(
                        rs.getObject("execution_id", UUID.class),
                        MarketNewsExecutionState.valueOf(rs.getString("state")),
                        rs.getInt("call_count"),
                        rs.getInt("completed_work_unit_count"),
                        rs.getInt("failed_work_unit_count"),
                        rs.getInt("truncated_work_unit_count")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MarketNewsCollectionExecution> findResumableExecution(String requestId) {
        Optional<ResumableExecutionRow> execution = jdbcClient
                .sql("""
                    SELECT execution.execution_id, execution.request_id,
                           execution.execution_type, execution.policy_version,
                           execution.scheduled_at, execution.overlap_cutoff,
                           execution.call_budget, execution.call_count,
                           execution.planned_work_unit_count,
                           (
                               SELECT count(*)
                               FROM market_news_collection_work_unit unit
                               WHERE unit.execution_id = execution.execution_id
                                 AND unit.state = 'COMPLETED'
                           ) AS completed_work_unit_count,
                           (
                               SELECT count(*)
                               FROM market_news_collection_work_unit unit
                               WHERE unit.execution_id = execution.execution_id
                                 AND unit.state = 'FAILED'
                           ) AS failed_work_unit_count,
                           (
                               SELECT count(*)
                               FROM market_news_collection_work_unit unit
                               WHERE unit.execution_id = execution.execution_id
                                 AND unit.state = 'TRUNCATED'
                           ) AS truncated_work_unit_count,
                           (
                               SELECT count(*)
                               FROM market_news_collection_work_unit unit
                               WHERE unit.execution_id = execution.execution_id
                                 AND unit.state = 'SKIPPED_BUDGET'
                           ) AS skipped_budget_work_unit_count,
                           (
                               SELECT unit.failure_kind
                               FROM market_news_collection_work_unit unit
                               WHERE unit.execution_id = execution.execution_id
                                 AND unit.state IN ('FAILED', 'SKIPPED_BUDGET')
                                 AND unit.failure_kind IN (
                                     'AUTHENTICATION', 'DAILY_QUOTA', 'DAILY_CALL_BUDGET'
                                 )
                               ORDER BY unit.unit_order
                               LIMIT 1
                           ) AS stopping_failure_kind
                    FROM market_news_collection_execution execution
                    WHERE execution.request_id = :requestId
                      AND execution.state IN ('PLANNED', 'RUNNING')
                    """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new ResumableExecutionRow(
                        rs.getObject("execution_id", UUID.class),
                        rs.getString("request_id"),
                        rs.getString("execution_type"),
                        rs.getString("policy_version"),
                        rs.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
                        nullableInstant(rs, "overlap_cutoff"),
                        rs.getInt("call_budget"),
                        rs.getInt("call_count"),
                        rs.getInt("planned_work_unit_count"),
                        rs.getInt("completed_work_unit_count"),
                        rs.getInt("failed_work_unit_count"),
                        rs.getInt("truncated_work_unit_count"),
                        rs.getInt("skipped_budget_work_unit_count"),
                        rs.getString("stopping_failure_kind") == null
                                ? null
                                : MarketNewsFailureKind.valueOf(rs.getString("stopping_failure_kind"))))
                .optional();
        if (execution.isEmpty()) {
            return Optional.empty();
        }

        ResumableExecutionRow row = execution.get();
        List<ResumableWorkUnitRow> unfinished = jdbcClient
                .sql("""
                    SELECT work_unit_id, unit_order, scope_kind, scope_type,
                           region_code, complex_id, category, query_text,
                           last_provider_start, call_count, raw_item_count,
                           oldest_provided_at
                    FROM market_news_collection_work_unit
                    WHERE execution_id = :executionId
                      AND state IN ('PLANNED', 'RUNNING')
                    ORDER BY unit_order
                    """)
                .param("executionId", row.executionId())
                .query((rs, rowNum) -> new ResumableWorkUnitRow(
                        rs.getObject("work_unit_id", UUID.class),
                        rs.getInt("unit_order"),
                        MarketNewsWorkUnitKind.valueOf(rs.getString("scope_kind")),
                        MarketNewsScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("region_code"),
                        rs.getObject("complex_id", Long.class),
                        rs.getString("category") == null ? null : MarketNewsCategory.valueOf(rs.getString("category")),
                        rs.getString("query_text"),
                        rs.getInt("last_provider_start"),
                        rs.getInt("call_count"),
                        rs.getInt("raw_item_count"),
                        nullableInstant(rs, "oldest_provided_at")))
                .list();

        Map<String, String> sidoNames =
                rootSidos().stream().collect(java.util.stream.Collectors.toMap(SidoRow::code, SidoRow::name));
        Map<String, List<NewsComplexEvidence>> corpusBySido =
                unfinished.stream().anyMatch(unit -> unit.kind() == MarketNewsWorkUnitKind.SIDO)
                        ? loadComplexCorpus(null).stream()
                                .collect(java.util.stream.Collectors.groupingBy(
                                        complex -> complex.region().sidoCode(),
                                        LinkedHashMap::new,
                                        java.util.stream.Collectors.toList()))
                        : Map.of();
        Map<Long, NewsComplexEvidence> complexById =
                unfinished.stream().anyMatch(unit -> unit.kind() == MarketNewsWorkUnitKind.MAJOR_COMPLEX)
                        ? loadComplexCorpus(null).stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        NewsComplexEvidence::complexId,
                                        value -> value,
                                        (left, right) -> left,
                                        LinkedHashMap::new))
                        : Map.of();
        List<MarketNewsWorkUnitSpec> workUnits = unfinished.stream()
                .map(unit -> resumableSpec(unit, sidoNames, corpusBySido, complexById))
                .toList();
        return Optional.of(new MarketNewsCollectionExecution(
                row.executionId(),
                row.requestId(),
                row.executionType(),
                row.policyVersion(),
                row.scheduledAt(),
                row.overlapCutoff(),
                row.callBudget(),
                row.consumedCallCount(),
                row.plannedWorkUnitCount(),
                row.completedWorkUnitCount(),
                row.failedWorkUnitCount(),
                row.truncatedWorkUnitCount(),
                row.skippedBudgetWorkUnitCount(),
                row.stoppingFailureKind(),
                workUnits));
    }

    private MarketNewsWorkUnitSpec resumableSpec(
            ResumableWorkUnitRow unit,
            Map<String, String> sidoNames,
            Map<String, List<NewsComplexEvidence>> corpusBySido,
            Map<Long, NewsComplexEvidence> complexById) {
        NewsComplexEvidence focusComplex = unit.complexId() == null ? null : complexById.get(unit.complexId());
        if (unit.kind() == MarketNewsWorkUnitKind.MAJOR_COMPLEX && focusComplex == null) {
            throw new IllegalStateException("재개할 주요 단지 evidence를 찾을 수 없습니다: " + unit.complexId());
        }
        List<NewsComplexEvidence> corpus =
                switch (unit.kind()) {
                    case NATIONAL_CATEGORY -> List.of();
                    case SIDO -> corpusBySido.getOrDefault(unit.regionCode(), List.of());
                    case MAJOR_COMPLEX -> List.of(focusComplex);
                };
        return new MarketNewsWorkUnitSpec(
                unit.workUnitId(),
                unit.order(),
                unit.kind(),
                unit.scopeType(),
                unit.regionCode(),
                unit.regionCode() == null ? null : sidoNames.get(unit.regionCode()),
                unit.category(),
                unit.query(),
                focusComplex,
                corpus,
                unit.lastProviderStart() == 0 ? 1 : unit.lastProviderStart() + 100,
                unit.callCount(),
                unit.rawItemCount(),
                unit.oldestProvidedAt());
    }

    @Override
    @Transactional
    public MarketNewsCollectionExecution planGeneral(
            String requestId, Instant scheduledAt, Instant overlapCutoff, int callBudget) {
        boolean bootstrap = requestId != null && requestId.startsWith("BOOTSTRAP");
        String executionType = bootstrap ? "BOOTSTRAP" : "GENERAL";
        if (!bootstrap) {
            overlapCutoff = latestSuccessfulCutoff(executionType, overlapCutoff);
        }
        List<MarketNewsWorkUnitSpec> specs = new ArrayList<>();
        int order = 1;
        for (var template : policyRegistry.nationwide()) {
            specs.add(new MarketNewsWorkUnitSpec(
                    UUID.randomUUID(),
                    order++,
                    MarketNewsWorkUnitKind.NATIONAL_CATEGORY,
                    MarketNewsScopeType.NATIONWIDE,
                    null,
                    null,
                    template.category(),
                    template.query(),
                    null,
                    List.of()));
        }
        Map<String, List<NewsComplexEvidence>> corpusBySido = loadComplexCorpus(null).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        complex -> complex.region().sidoCode(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (SidoRow sido : rootSidos()) {
            List<NewsComplexEvidence> corpus = corpusBySido.getOrDefault(sido.code(), List.of());
            for (String query : policyRegistry.sido(sido.name())) {
                specs.add(new MarketNewsWorkUnitSpec(
                        UUID.randomUUID(),
                        order++,
                        MarketNewsWorkUnitKind.SIDO,
                        MarketNewsScopeType.SIDO,
                        sido.code(),
                        sido.name(),
                        null,
                        query,
                        null,
                        corpus));
            }
        }
        return insertPlan(requestId, executionType, scheduledAt, overlapCutoff, callBudget, specs);
    }

    @Override
    @Transactional
    public MarketNewsCollectionExecution planMajorComplex(
            String requestId, Instant scheduledAt, Instant overlapCutoff, int callBudget) {
        overlapCutoff = latestSuccessfulCutoff("MAJOR_COMPLEX", overlapCutoff);
        List<NewsComplexEvidence> selected = loadSelectedMajorComplexes();
        if (selected.size() != 200) {
            throw new IllegalStateException("정상 발행된 주요 단지 200개 selection이 필요합니다");
        }
        List<MarketNewsWorkUnitSpec> specs = new ArrayList<>(250);
        int order = 1;
        for (NewsComplexEvidence complex : selected) {
            NewsRegionEvidence region = complex.region();
            for (String query : policyRegistry.majorComplex(
                    region.sigunguName(), region.dongName(), complex.canonicalName(), complex.isQualityChallenge())) {
                specs.add(new MarketNewsWorkUnitSpec(
                        UUID.randomUUID(),
                        order++,
                        MarketNewsWorkUnitKind.MAJOR_COMPLEX,
                        MarketNewsScopeType.SIDO,
                        region.sidoCode(),
                        region.sidoName(),
                        null,
                        query,
                        complex,
                        List.of(complex)));
            }
        }
        return insertPlan(requestId, "MAJOR_COMPLEX", scheduledAt, overlapCutoff, callBudget, specs);
    }

    private MarketNewsCollectionExecution insertPlan(
            String requestId,
            String executionType,
            Instant scheduledAt,
            Instant overlapCutoff,
            int callBudget,
            List<MarketNewsWorkUnitSpec> specs) {
        UUID executionId = UUID.randomUUID();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, overlap_cutoff, state, call_budget,
                        planned_work_unit_count
                    ) VALUES (
                        :executionId, :requestId, :executionType, :policyVersion,
                        :scheduledAt, :overlapCutoff, 'PLANNED', :callBudget, :plannedCount
                    )
                    """)
                .param("executionId", executionId)
                .param("requestId", requestId)
                .param("executionType", executionType)
                .param("policyVersion", MarketNewsQueryPolicyRegistry.POLICY_VERSION)
                .param("scheduledAt", utc(scheduledAt))
                .param("overlapCutoff", utc(overlapCutoff))
                .param("callBudget", callBudget)
                .param("plannedCount", specs.size())
                .update();
        for (MarketNewsWorkUnitSpec spec : specs) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_collection_work_unit (
                            work_unit_id, execution_id, unit_order, scope_kind, scope_type,
                            region_code, complex_id, category, query_text, cutoff_at, state
                        ) VALUES (
                            :workUnitId, :executionId, :unitOrder, :scopeKind, :scopeType,
                            :regionCode, :complexId, :category, :queryText, :cutoffAt, 'PLANNED'
                        )
                        """)
                    .param("workUnitId", spec.workUnitId())
                    .param("executionId", executionId)
                    .param("unitOrder", spec.order())
                    .param("scopeKind", spec.kind().name())
                    .param("scopeType", spec.scopeType().name())
                    .param("regionCode", spec.regionCode())
                    .param(
                            "complexId",
                            spec.focusComplex() == null
                                    ? null
                                    : spec.focusComplex().complexId())
                    .param(
                            "category",
                            spec.plannedCategory() == null
                                    ? null
                                    : spec.plannedCategory().name())
                    .param("queryText", spec.query())
                    .param("cutoffAt", utc(overlapCutoff))
                    .update();
        }
        return new MarketNewsCollectionExecution(
                executionId,
                requestId,
                executionType,
                MarketNewsQueryPolicyRegistry.POLICY_VERSION,
                scheduledAt,
                overlapCutoff,
                callBudget,
                0,
                specs.size(),
                0,
                0,
                0,
                0,
                null,
                List.copyOf(specs));
    }

    @Override
    public void startExecution(UUID executionId, Instant startedAt) {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_execution
                    SET state = 'RUNNING', started_at = :startedAt
                    WHERE execution_id = :executionId AND state = 'PLANNED'
                    """)
                .param("executionId", executionId)
                .param("startedAt", utc(startedAt))
                .update();
    }

    @Override
    public void startWorkUnit(UUID workUnitId, Instant startedAt) {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'RUNNING', started_at = :startedAt
                    WHERE work_unit_id = :workUnitId AND state = 'PLANNED'
                    """)
                .param("workUnitId", workUnitId)
                .param("startedAt", utc(startedAt))
                .update();
    }

    @Override
    @Transactional
    public void saveRawItems(UUID workUnitId, List<NewsProviderItem> items, Instant receivedAt) {
        for (NewsProviderItem item : items) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_raw_item (
                            work_unit_id, provider_start, provider_rank,
                            title_raw, original_link_raw, link_raw, description_raw,
                            pub_date_raw, received_at
                        ) VALUES (
                            :workUnitId, :providerStart, :providerRank,
                            :title, :originalLink, :link, :description,
                            :pubDate, :receivedAt
                        )
                        ON CONFLICT (work_unit_id, provider_start, provider_rank) DO NOTHING
                        """)
                    .param("workUnitId", workUnitId)
                    .param("providerStart", item.providerStart())
                    .param("providerRank", item.providerRank())
                    .param("title", item.title())
                    .param("originalLink", item.originalLink())
                    .param("link", item.link())
                    .param("description", item.description())
                    .param("pubDate", item.pubDate())
                    .param("receivedAt", utc(receivedAt))
                    .update();
        }
    }

    @Override
    public void requireRawItemMatch(UUID workUnitId, NewsProviderItem rawItem) {
        if (!rawItemMatches(workUnitId, rawItem)) {
            throw new RawNewsPositionConflictException();
        }
    }

    public boolean rawItemMatches(UUID workUnitId, NewsProviderItem rawItem) {
        return jdbcClient
                .sql("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM market_news_raw_item
                        WHERE work_unit_id = :workUnitId
                          AND provider_start = :providerStart
                          AND provider_rank = :providerRank
                          AND title_raw IS NOT DISTINCT FROM :title
                          AND original_link_raw IS NOT DISTINCT FROM :originalLink
                          AND link_raw IS NOT DISTINCT FROM :link
                          AND description_raw IS NOT DISTINCT FROM :description
                          AND pub_date_raw IS NOT DISTINCT FROM :pubDate
                    )
                    """)
                .param("workUnitId", workUnitId)
                .param("providerStart", rawItem.providerStart())
                .param("providerRank", rawItem.providerRank())
                .param("title", rawItem.title())
                .param("originalLink", rawItem.originalLink())
                .param("link", rawItem.link())
                .param("description", rawItem.description())
                .param("pubDate", rawItem.pubDate())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void recordWorkUnitPageProgress(
            UUID workUnitId, int providerStart, int callCount, int rawItemCount, Instant oldestProvidedAt) {
        int updated = jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET last_provider_start = GREATEST(last_provider_start, :providerStart),
                        call_count = GREATEST(call_count, :callCount),
                        raw_item_count = GREATEST(raw_item_count, :rawItemCount),
                        oldest_provided_at = :oldestProvidedAt
                    WHERE work_unit_id = :workUnitId
                      AND state = 'RUNNING'
                    """)
                .param("providerStart", providerStart)
                .param("callCount", callCount)
                .param("rawItemCount", rawItemCount)
                .param("oldestProvidedAt", utc(oldestProvidedAt))
                .param("workUnitId", workUnitId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("수집 중인 뉴스 work unit progress를 저장할 수 없습니다");
        }
    }

    @Override
    public void completeWorkUnitPage(
            UUID workUnitId,
            int providerStart,
            int callCount,
            int rawItemCount,
            Instant oldestProvidedAt,
            Instant completedAt) {
        int updated = jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET last_provider_start = GREATEST(last_provider_start, :providerStart),
                        call_count = GREATEST(call_count, :callCount),
                        raw_item_count = GREATEST(raw_item_count, :rawItemCount),
                        oldest_provided_at = :oldestProvidedAt,
                        cutoff_reached = true,
                        state = 'COMPLETED',
                        failure_kind = NULL,
                        completed_at = :completedAt
                    WHERE work_unit_id = :workUnitId
                      AND state = 'RUNNING'
                    """)
                .param("providerStart", providerStart)
                .param("callCount", callCount)
                .param("rawItemCount", rawItemCount)
                .param("oldestProvidedAt", utc(oldestProvidedAt))
                .param("completedAt", utc(completedAt))
                .param("workUnitId", workUnitId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("수집 중인 뉴스 work unit 완료 page를 저장할 수 없습니다");
        }
    }

    @Override
    public long upsertArticle(NormalizedNewsItem item, Instant seenAt) {
        return jdbcClient
                .sql("""
                    INSERT INTO market_news_article (
                        provider, canonical_url_hash, public_url, title, provided_at,
                        first_seen_at, last_seen_at
                    ) VALUES (
                        'NAVER', :urlHash, :publicUrl, :title, :providedAt,
                        :seenAt, :seenAt
                    )
                    ON CONFLICT (provider, canonical_url_hash) DO UPDATE
                    SET last_seen_at = GREATEST(market_news_article.last_seen_at, EXCLUDED.last_seen_at),
                        public_url = EXCLUDED.public_url,
                        title = EXCLUDED.title,
                        provided_at = EXCLUDED.provided_at
                    RETURNING article_id
                    """)
                .param("urlHash", item.canonicalUrlHash())
                .param("publicUrl", item.publicUrl())
                .param("title", item.title())
                .param("providedAt", utc(item.providedAt()))
                .param("seenAt", utc(seenAt))
                .query(Long.class)
                .single();
    }

    @Override
    public void linkRawItem(UUID workUnitId, NewsProviderItem rawItem, long articleId) {
        int updated = jdbcClient
                .sql("""
                    UPDATE market_news_raw_item
                    SET article_id = :articleId, rejection_reason = NULL
                    WHERE work_unit_id = :workUnitId
                      AND provider_start = :providerStart
                      AND provider_rank = :providerRank
                      AND title_raw IS NOT DISTINCT FROM :title
                      AND original_link_raw IS NOT DISTINCT FROM :originalLink
                      AND link_raw IS NOT DISTINCT FROM :link
                      AND description_raw IS NOT DISTINCT FROM :description
                      AND pub_date_raw IS NOT DISTINCT FROM :pubDate
                    """)
                .param("articleId", articleId)
                .param("workUnitId", workUnitId)
                .param("providerStart", rawItem.providerStart())
                .param("providerRank", rawItem.providerRank())
                .param("title", rawItem.title())
                .param("originalLink", rawItem.originalLink())
                .param("link", rawItem.link())
                .param("description", rawItem.description())
                .param("pubDate", rawItem.pubDate())
                .update();
        if (updated != 1) {
            throw new RawNewsPositionConflictException();
        }
    }

    @Override
    public void rejectRawItem(UUID workUnitId, NewsProviderItem rawItem, NewsRejectionReason reason) {
        int updated = jdbcClient
                .sql("""
                    UPDATE market_news_raw_item
                    SET rejection_reason = :reason, article_id = NULL
                    WHERE work_unit_id = :workUnitId
                      AND provider_start = :providerStart
                      AND provider_rank = :providerRank
                      AND title_raw IS NOT DISTINCT FROM :title
                      AND original_link_raw IS NOT DISTINCT FROM :originalLink
                      AND link_raw IS NOT DISTINCT FROM :link
                      AND description_raw IS NOT DISTINCT FROM :description
                      AND pub_date_raw IS NOT DISTINCT FROM :pubDate
                    """)
                .param("reason", reason.name())
                .param("workUnitId", workUnitId)
                .param("providerStart", rawItem.providerStart())
                .param("providerRank", rawItem.providerRank())
                .param("title", rawItem.title())
                .param("originalLink", rawItem.originalLink())
                .param("link", rawItem.link())
                .param("description", rawItem.description())
                .param("pubDate", rawItem.pubDate())
                .update();
        if (updated != 1) {
            throw new RawNewsPositionConflictException();
        }
    }

    @Override
    public void saveRelation(
            long articleId, String policyVersion, MarketNewsCategory category, MarketNewsRelationMatch relation) {
        jdbcClient
                .sql("""
                    INSERT INTO market_news_relation (
                        article_id, policy_version, category, relation_type,
                        region_code, complex_id, matched_tokens
                    ) VALUES (
                        :articleId, :policyVersion, :category, :relationType,
                        :regionCode, :complexId, :matchedTokens
                    )
                    ON CONFLICT DO NOTHING
                    """)
                .param("articleId", articleId)
                .param("policyVersion", policyVersion)
                .param("category", category.name())
                .param("relationType", relation.relationType().name())
                .param("regionCode", relation.regionCode())
                .param("complexId", relation.complexId())
                .param("matchedTokens", relation.matchedTokens().toArray(String[]::new))
                .update();
    }

    @Override
    public void finishWorkUnit(
            UUID workUnitId,
            MarketNewsWorkUnitState state,
            int callCount,
            int rawItemCount,
            Instant oldestProvidedAt,
            boolean cutoffReached,
            MarketNewsFailureKind failureKind,
            Instant completedAt) {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = :state,
                        call_count = :callCount,
                        raw_item_count = :rawItemCount,
                        oldest_provided_at = :oldestProvidedAt,
                        cutoff_reached = :cutoffReached,
                        failure_kind = :failureKind,
                        completed_at = :completedAt
                    WHERE work_unit_id = :workUnitId AND state = 'RUNNING'
                    """)
                .param("state", state.name())
                .param("callCount", callCount)
                .param("rawItemCount", rawItemCount)
                .param("oldestProvidedAt", utc(oldestProvidedAt))
                .param("cutoffReached", cutoffReached)
                .param("failureKind", failureKind == null ? null : failureKind.name())
                .param("completedAt", utc(completedAt))
                .param("workUnitId", workUnitId)
                .update();
    }

    @Override
    @Transactional
    public void incrementExecutionCallCount(UUID executionId) {
        jdbcClient
                .sql("""
                    SELECT pg_advisory_xact_lock(hashtext(
                        'market-news-daily-budget:'
                        || (scheduled_at AT TIME ZONE 'Asia/Seoul')::date::text
                    ))
                    FROM market_news_collection_execution
                    WHERE execution_id = :executionId
                    """)
                .param("executionId", executionId)
                .query((rs, rowNum) -> 1)
                .single();
        int updated = jdbcClient.sql("""
                    UPDATE market_news_collection_execution execution
                    SET call_count = call_count + 1
                    WHERE execution_id = :executionId
                      AND state = 'RUNNING'
                      AND call_count < call_budget
                      AND (
                          SELECT COALESCE(sum(other.call_count), 0)
                          FROM market_news_collection_execution other
                          WHERE (other.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                                = (execution.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                      ) < (
                          SELECT COALESCE(min(other.call_budget), execution.call_budget)
                          FROM market_news_collection_execution other
                          WHERE (other.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                                = (execution.scheduled_at AT TIME ZONE 'Asia/Seoul')::date
                      )
                    """).param("executionId", executionId).update();
        if (updated != 1) {
            throw new NewsCallBudgetExceededException();
        }
    }

    @Override
    public void finishExecution(
            UUID executionId, MarketNewsExecutionState state, MarketNewsFailureKind failureKind, Instant completedAt) {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_execution execution
                    SET state = :state,
                        completed_at = :completedAt,
                        failure_kind = :failureKind,
                        completed_work_unit_count = counts.completed_count,
                        truncated_work_unit_count = counts.truncated_count,
                        failed_work_unit_count = counts.failed_count,
                        skipped_budget_work_unit_count = counts.skipped_count,
                        raw_item_count = counts.raw_count,
                        article_count = counts.article_count,
                        relation_count = counts.relation_count,
                        bootstrap_truncated = counts.truncated_count > 0
                    FROM (
                        SELECT
                            (
                                SELECT count(*) FROM market_news_collection_work_unit
                                WHERE execution_id = :executionId AND state = 'COMPLETED'
                            )::integer AS completed_count,
                            (
                                SELECT count(*) FROM market_news_collection_work_unit
                                WHERE execution_id = :executionId AND state = 'TRUNCATED'
                            )::integer AS truncated_count,
                            (
                                SELECT count(*) FROM market_news_collection_work_unit
                                WHERE execution_id = :executionId AND state = 'FAILED'
                            )::integer AS failed_count,
                            (
                                SELECT count(*) FROM market_news_collection_work_unit
                                WHERE execution_id = :executionId AND state = 'SKIPPED_BUDGET'
                            )::integer AS skipped_count,
                            (
                                SELECT count(*)
                                FROM market_news_raw_item raw
                                JOIN market_news_collection_work_unit unit
                                  ON unit.work_unit_id = raw.work_unit_id
                                WHERE unit.execution_id = :executionId
                            )::integer AS raw_count,
                            (
                                SELECT count(DISTINCT raw.article_id)
                                FROM market_news_raw_item raw
                                JOIN market_news_collection_work_unit unit
                                  ON unit.work_unit_id = raw.work_unit_id
                                WHERE unit.execution_id = :executionId
                            )::integer AS article_count,
                            (
                                SELECT count(*)
                                FROM market_news_relation relation
                                WHERE EXISTS (
                                    SELECT 1
                                    FROM market_news_raw_item raw
                                    JOIN market_news_collection_work_unit unit
                                      ON unit.work_unit_id = raw.work_unit_id
                                    WHERE unit.execution_id = :executionId
                                      AND raw.article_id = relation.article_id
                                )
                            )::integer AS relation_count
                    ) counts
                    WHERE execution.execution_id = :executionId
                      AND execution.state = 'RUNNING'
                    """)
                .param("state", state.name())
                .param("failureKind", failureKind == null ? null : failureKind.name())
                .param("completedAt", utc(completedAt))
                .param("executionId", executionId)
                .update();
    }

    @Override
    @Transactional
    public List<PublishedNewsSnapshot> publishEligibleScopes(UUID executionId, Instant generatedAt) {
        ExecutionPublicationRow execution = jdbcClient
                .sql("""
                    SELECT execution_type, policy_version
                    FROM market_news_collection_execution
                    WHERE execution_id = :executionId
                    """)
                .param("executionId", executionId)
                .query((rs, rowNum) ->
                        new ExecutionPublicationRow(rs.getString("execution_type"), rs.getString("policy_version")))
                .single();
        if ("MAJOR_COMPLEX".equals(execution.executionType())) {
            return publishMajorComplexScopes(executionId, generatedAt, execution.policyVersion());
        }
        boolean bootstrap = "BOOTSTRAP".equals(execution.executionType());
        List<ScopeRow> eligibleScopes = eligibleGeneralScopes(executionId, bootstrap);
        List<PublishedNewsSnapshot> published = new ArrayList<>();
        for (ScopeRow scope : eligibleScopes) {
            publishScope(executionId, execution.policyVersion(), scope, generatedAt, bootstrap)
                    .ifPresent(published::add);
        }
        return List.copyOf(published);
    }

    @Override
    public void markRemainingSkippedBudget(UUID executionId, Instant completedAt) {
        jdbcClient
                .sql("""
                    UPDATE market_news_collection_work_unit
                    SET state = 'SKIPPED_BUDGET', completed_at = :completedAt, failure_kind = 'DAILY_CALL_BUDGET'
                    WHERE execution_id = :executionId AND state IN ('PLANNED', 'RUNNING')
                    """)
                .param("executionId", executionId)
                .param("completedAt", utc(completedAt))
                .update();
    }

    private List<ScopeRow> eligibleGeneralScopes(UUID executionId, boolean bootstrap) {
        List<ScopeRow> scopes = new ArrayList<>();
        long nationalCompleted = jdbcClient
                .sql("""
                    SELECT count(*) FROM market_news_collection_work_unit
                    WHERE execution_id = :executionId
                      AND scope_kind = 'NATIONAL_CATEGORY'
                      AND (
                          state = 'COMPLETED'
                          OR (CAST(:bootstrap AS boolean) AND state = 'TRUNCATED')
                      )
                    """)
                .param("executionId", executionId)
                .param("bootstrap", bootstrap)
                .query(Long.class)
                .single();
        if (nationalCompleted == 6) {
            scopes.add(new ScopeRow(MarketNewsScopeType.NATIONWIDE, null));
        }
        scopes.addAll(jdbcClient
                .sql("""
                    SELECT region_code
                    FROM market_news_collection_work_unit
                    WHERE execution_id = :executionId
                      AND scope_kind = 'SIDO'
                    GROUP BY region_code
                    HAVING count(*) FILTER (
                        WHERE state = 'COMPLETED'
                           OR (CAST(:bootstrap AS boolean) AND state = 'TRUNCATED')
                    ) = count(*)
                    ORDER BY min(unit_order)
                    """)
                .param("executionId", executionId)
                .param("bootstrap", bootstrap)
                .query((rs, rowNum) -> new ScopeRow(MarketNewsScopeType.SIDO, rs.getString("region_code")))
                .list());
        return scopes;
    }

    private List<PublishedNewsSnapshot> publishMajorComplexScopes(
            UUID executionId, Instant generatedAt, String policyVersion) {
        List<ScopeRow> scopes = jdbcClient
                .sql("""
                    SELECT region_code
                    FROM market_news_collection_work_unit
                    WHERE execution_id = :executionId
                      AND scope_kind = 'MAJOR_COMPLEX'
                    GROUP BY region_code
                    HAVING count(*) FILTER (WHERE state = 'COMPLETED') = count(*)
                    ORDER BY region_code
                    """)
                .param("executionId", executionId)
                .query((rs, rowNum) -> new ScopeRow(MarketNewsScopeType.SIDO, rs.getString("region_code")))
                .list();
        List<PublishedNewsSnapshot> published = new ArrayList<>();
        for (ScopeRow scope : scopes) {
            publishScope(executionId, policyVersion, scope, generatedAt, false).ifPresent(published::add);
        }
        return List.copyOf(published);
    }

    private Instant latestSuccessfulCutoff(String executionType, Instant fallback) {
        return jdbcClient
                .sql("""
                    SELECT completed_at
                    FROM market_news_collection_execution
                    WHERE execution_type = :executionType
                      AND state = 'COMPLETED'
                    ORDER BY completed_at DESC
                    LIMIT 1
                    """)
                .param("executionType", executionType)
                .query(OffsetDateTime.class)
                .optional()
                .map(value -> value.toInstant().minus(java.time.Duration.ofHours(2)))
                .orElse(fallback);
    }

    private Optional<PublishedNewsSnapshot> publishScope(
            UUID executionId, String policyVersion, ScopeRow scope, Instant generatedAt, boolean includeTruncated) {
        UUID snapshotId = UUID.randomUUID();
        Instant cutoff = jdbcClient
                .sql("SELECT COALESCE(MAX(completed_at), :generatedAt) FROM market_news_collection_work_unit "
                        + "WHERE execution_id = :executionId "
                        + "AND (state = 'COMPLETED' OR (CAST(:includeTruncated AS boolean) AND state = 'TRUNCATED'))")
                .param("generatedAt", utc(generatedAt))
                .param("executionId", executionId)
                .param("includeTruncated", includeTruncated)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type,
                        region_code, build_status, generated_at, data_cutoff
                    ) VALUES (
                        :snapshotId, :executionId, :policyVersion, :scopeType,
                        :regionCode, 'BUILDING', :generatedAt, :dataCutoff
                    )
                    """)
                .param("snapshotId", snapshotId)
                .param("executionId", executionId)
                .param("policyVersion", policyVersion)
                .param("scopeType", scope.type().name())
                .param("regionCode", scope.regionCode())
                .param("generatedAt", utc(generatedAt))
                .param("dataCutoff", utc(cutoff))
                .update();
        insertSnapshotItems(snapshotId, executionId, policyVersion, scope, generatedAt, includeTruncated);
        mergePreviousSnapshotItems(snapshotId, scope, generatedAt);
        jdbcClient.sql("""
                    UPDATE market_news_snapshot replacement
                    SET item_count = (
                        SELECT count(*) FROM market_news_snapshot_item item
                        WHERE item.snapshot_id = replacement.snapshot_id
                    )
                    WHERE replacement.snapshot_id = :snapshotId
                    """).param("snapshotId", snapshotId).update();
        if (!passesSnapshotHardGate(snapshotId, generatedAt)) {
            jdbcClient
                    .sql("UPDATE market_news_snapshot SET build_status = 'REJECTED' WHERE snapshot_id = :snapshotId")
                    .param("snapshotId", snapshotId)
                    .update();
            return Optional.empty();
        }
        jdbcClient
                .sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'SUPERSEDED', superseded_by_snapshot_id = :snapshotId
                    WHERE build_status = 'PUBLISHED'
                      AND scope_type = :scopeType
                      AND ((CAST(:regionCode AS varchar) IS NULL AND region_code IS NULL)
                           OR region_code = :regionCode)
                    """)
                .param("snapshotId", snapshotId)
                .param("scopeType", scope.type().name())
                .param("regionCode", scope.regionCode())
                .update();
        jdbcClient
                .sql("UPDATE market_news_snapshot SET build_status = 'PUBLISHED' WHERE snapshot_id = :snapshotId")
                .param("snapshotId", snapshotId)
                .update();
        writeNewsSnapshotPublishedEvent(executionId, snapshotId, scope, generatedAt, cutoff);
        return Optional.of(
                new PublishedNewsSnapshot(snapshotId, scope.type(), scope.regionCode(), generatedAt, cutoff));
    }

    private void writeNewsSnapshotPublishedEvent(
            UUID executionId, UUID snapshotId, ScopeRow scope, Instant occurredAt, Instant dataCutoff) {
        jdbcClient
                .sql("""
                    INSERT INTO event_outbox (
                        event_id, topic_name, event_type, schema_version, occurred_at,
                        producer, aggregate_type, aggregate_id, aggregate_version,
                        correlation_id, causation_id, trace_id, payload
                    ) VALUES (
                        gen_random_uuid(), 'property.insight-events.v1',
                        'NewsSnapshotPublished', 1, :occurredAt,
                        'property-data', 'NewsSnapshot', :aggregateId, 1,
                        :correlationId, NULL, :correlationId,
                        jsonb_build_object(
                            'snapshotId', :aggregateId,
                            'scopeType', :scopeType,
                            'regionCode', CAST(:regionCode AS varchar),
                            'dataCutoff', :dataCutoff
                        )
                    )
                    ON CONFLICT (event_type, aggregate_id, aggregate_version) DO NOTHING
                    """)
                .param("occurredAt", utc(occurredAt))
                .param("aggregateId", snapshotId.toString())
                .param("correlationId", executionId.toString())
                .param("scopeType", scope.type().name())
                .param("regionCode", scope.regionCode())
                .param("dataCutoff", utc(dataCutoff))
                .update();
    }

    private boolean passesSnapshotHardGate(UUID snapshotId, Instant generatedAt) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
                    SELECT
                        snapshot.item_count = count(item.article_id)
                        AND count(item.article_id) = count(DISTINCT article.canonical_url_hash)
                        AND count(*) FILTER (
                            WHERE btrim(article.title) = ''
                               OR article.public_url !~* '^https?://[^/@[:space:]]+([/:?#]|$)'
                               OR article.provided_at > :generatedAt
                               OR article.provided_at < :retentionCutoff
                               OR relation.article_id <> item.article_id
                               OR relation.category <> item.category
                               OR relation.policy_version <> snapshot.policy_version
                        ) = 0
                    FROM market_news_snapshot snapshot
                    LEFT JOIN market_news_snapshot_item item
                      ON item.snapshot_id = snapshot.snapshot_id
                    LEFT JOIN market_news_article article
                      ON article.article_id = item.article_id
                    LEFT JOIN market_news_relation relation
                      ON relation.relation_id = item.relation_id
                    WHERE snapshot.snapshot_id = :snapshotId
                    GROUP BY snapshot.item_count
                    """)
                .param("snapshotId", snapshotId)
                .param("generatedAt", utc(generatedAt))
                .param("retentionCutoff", utc(generatedAt.minus(java.time.Duration.ofDays(30))))
                .query(Boolean.class)
                .single());
    }

    private void insertSnapshotItems(
            UUID snapshotId,
            UUID executionId,
            String policyVersion,
            ScopeRow scope,
            Instant generatedAt,
            boolean includeTruncated) {
        jdbcClient
                .sql("""
                    WITH candidate AS (
                        SELECT DISTINCT ON (article.article_id)
                               article.article_id,
                               relation.relation_id,
                               relation.category,
                               article.provided_at,
                               MIN(raw.provider_rank) OVER (PARTITION BY article.article_id) AS provider_rank,
                               CASE relation.relation_type
                                   WHEN 'DIRECT_COMPLEX' THEN 1
                                   WHEN 'SAME_DONG' THEN 2
                                   WHEN 'SAME_SIGUNGU' THEN 3
                                   WHEN 'SAME_SIDO' THEN 4
                                   ELSE 5
                               END AS relation_priority
                        FROM market_news_collection_work_unit unit
                        JOIN market_news_raw_item raw ON raw.work_unit_id = unit.work_unit_id
                        JOIN market_news_article article ON article.article_id = raw.article_id
                        JOIN market_news_relation relation
                          ON relation.article_id = article.article_id
                         AND relation.policy_version = :policyVersion
                        LEFT JOIN region relation_region ON relation_region.code = relation.region_code
                        LEFT JOIN region relation_parent1 ON relation_parent1.id = relation_region.parent_id
                        LEFT JOIN region relation_parent2 ON relation_parent2.id = relation_parent1.parent_id
                        WHERE unit.execution_id = :executionId
                          AND (
                              unit.state = 'COMPLETED'
                              OR (CAST(:includeTruncated AS boolean) AND unit.state = 'TRUNCATED')
                          )
                          AND article.provided_at >= :retentionCutoff
                          AND (
                              (:scopeType = 'NATIONWIDE' AND relation.relation_type = 'NATIONWIDE')
                              OR
                              (:scopeType = 'SIDO' AND COALESCE(
                                  CASE WHEN relation_region.region_type = 'si-do' THEN relation_region.code END,
                                  CASE WHEN relation_parent1.region_type = 'si-do' THEN relation_parent1.code END,
                                  CASE WHEN relation_parent2.region_type = 'si-do' THEN relation_parent2.code END,
                                  relation.region_code
                              ) = :regionCode)
                          )
                        ORDER BY article.article_id, relation_priority, relation.relation_id
                    ), ranked AS (
                        SELECT candidate.*,
                               row_number() OVER (
                                   ORDER BY provided_at DESC, provider_rank, article_id
                               ) AS sort_rank
                        FROM candidate
                    )
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    )
                    SELECT :snapshotId, article_id, relation_id, category, sort_rank, provider_rank
                    FROM ranked
                    ORDER BY sort_rank
                    """)
                .param("policyVersion", policyVersion)
                .param("executionId", executionId)
                .param("includeTruncated", includeTruncated)
                .param("retentionCutoff", utc(generatedAt.minus(java.time.Duration.ofDays(30))))
                .param("scopeType", scope.type().name())
                .param("regionCode", scope.regionCode())
                .param("snapshotId", snapshotId)
                .update();
    }

    private void mergePreviousSnapshotItems(UUID snapshotId, ScopeRow scope, Instant generatedAt) {
        jdbcClient
                .sql("""
                    WITH previous AS (
                        SELECT previous.snapshot_id
                        FROM market_news_snapshot previous
                        JOIN market_news_snapshot replacement
                          ON replacement.snapshot_id = :snapshotId
                         AND replacement.policy_version = previous.policy_version
                        WHERE previous.build_status IN ('PUBLISHED', 'SUPERSEDED')
                          AND previous.scope_type = :scopeType
                          AND ((CAST(:regionCode AS varchar) IS NULL AND previous.region_code IS NULL)
                               OR previous.region_code = :regionCode)
                        ORDER BY CASE previous.build_status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                                 previous.generated_at DESC
                        LIMIT 1
                    ), candidate AS (
                        SELECT item.article_id, item.relation_id, item.category, item.provider_rank,
                               article.provided_at
                        FROM market_news_snapshot_item item
                        JOIN previous ON previous.snapshot_id = item.snapshot_id
                        JOIN market_news_article article ON article.article_id = item.article_id
                        WHERE article.provided_at >= :retentionCutoff
                          AND NOT EXISTS (
                              SELECT 1 FROM market_news_snapshot_item existing
                              WHERE existing.snapshot_id = :snapshotId
                                AND existing.article_id = item.article_id
                          )
                    ), numbered AS (
                        SELECT candidate.*,
                               (SELECT COALESCE(MAX(sort_rank), 0)
                                FROM market_news_snapshot_item
                                WHERE snapshot_id = :snapshotId)
                               + row_number() OVER (
                                   ORDER BY provided_at DESC, provider_rank, article_id
                               ) AS sort_rank
                        FROM candidate
                    )
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    )
                    SELECT :snapshotId, article_id, relation_id, category, sort_rank, provider_rank
                    FROM numbered
                    """)
                .param("scopeType", scope.type().name())
                .param("regionCode", scope.regionCode())
                .param("retentionCutoff", utc(generatedAt.minus(java.time.Duration.ofDays(30))))
                .param("snapshotId", snapshotId)
                .update();
    }

    private List<SidoRow> rootSidos() {
        return jdbcClient
                .sql("""
                    SELECT code, name FROM region
                    WHERE parent_id IS NULL AND region_type = 'si-do'
                    ORDER BY code
                    """)
                .query((rs, rowNum) -> new SidoRow(rs.getString("code"), rs.getString("name")))
                .list();
    }

    private List<NewsComplexEvidence> loadSelectedMajorComplexes() {
        List<Long> ids = jdbcClient.sql("""
                    SELECT complex_id
                    FROM market_news_major_complex_selection
                    WHERE selection_status = 'PUBLISHED'
                      AND selection_week = (
                          SELECT MAX(selection_week)
                          FROM market_news_major_complex_selection
                          WHERE selection_status = 'PUBLISHED'
                      )
                    ORDER BY rank
                    """).query(Long.class).list();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, NewsComplexEvidence> byId = loadComplexCorpus(null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        NewsComplexEvidence::complexId, value -> value, (left, right) -> left, LinkedHashMap::new));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private List<NewsComplexEvidence> loadComplexCorpus(String sidoCode) {
        return jdbcClient
                .sql("""
                    WITH complex_region AS (
                        SELECT complex.id, complex.name, complex.trade_name,
                               complex.unit_cnt,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.name END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.name END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.name END
                               ) AS sido_name,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-gun-gu' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-gun-gu' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-gun-gu' THEN region2.code END,
                                   CASE WHEN region0.region_type = 'si-do' AND region0.code = '36'
                                        THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' AND region1.code = '36'
                                        THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' AND region2.code = '36'
                                        THEN region2.code END
                               ) AS sigungu_code,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-gun-gu' THEN region0.name END,
                                   CASE WHEN region1.region_type = 'si-gun-gu' THEN region1.name END,
                                   CASE WHEN region2.region_type = 'si-gun-gu' THEN region2.name END,
                                   CASE WHEN region0.region_type = 'si-do' AND region0.code = '36'
                                        THEN region0.name END,
                                   CASE WHEN region1.region_type = 'si-do' AND region1.code = '36'
                                        THEN region1.name END,
                                   CASE WHEN region2.region_type = 'si-do' AND region2.code = '36'
                                        THEN region2.name END
                               ) AS sigungu_name,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'eup-myeon-dong' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'eup-myeon-dong' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'eup-myeon-dong' THEN region2.code END
                               ) AS dong_code,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'eup-myeon-dong' THEN region0.name END,
                                   CASE WHEN region1.region_type = 'eup-myeon-dong' THEN region1.name END,
                                   CASE WHEN region2.region_type = 'eup-myeon-dong' THEN region2.name END
                               ) AS dong_name,
                               regexp_replace(lower(complex.name), '[^0-9a-z가-힣]', '', 'g') AS normalized_name
                        FROM complex
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                    ), name_catalog AS (
                        SELECT id AS complex_id, normalized_name
                        FROM complex_region
                        WHERE normalized_name <> ''
                        UNION ALL
                        SELECT id, regexp_replace(lower(trade_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex_region
                        WHERE trade_name IS NOT NULL AND btrim(trade_name) <> ''
                        UNION ALL
                        SELECT alias.complex_id,
                               regexp_replace(lower(alias.alias_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex_name_alias alias
                        WHERE alias.alias_type = 'ADMIN_ALIAS'
                          AND btrim(alias.alias_name) <> ''
                    ), duplicated AS (
                        SELECT normalized_name
                        FROM name_catalog
                        GROUP BY normalized_name
                        HAVING count(DISTINCT complex_id) > 1
                    )
                    SELECT complex_region.*,
                           EXISTS (
                               SELECT 1
                               FROM name_catalog names
                               JOIN duplicated USING (normalized_name)
                               WHERE names.complex_id = complex_region.id
                           ) AS duplicate_name,
                           COALESCE((
                               SELECT array_agg(alias.alias_name ORDER BY length(alias.alias_name) DESC, alias.id)
                               FROM complex_name_alias alias
                               WHERE alias.complex_id = complex_region.id
                                 AND alias.alias_type = 'ADMIN_ALIAS'
                           ), ARRAY[]::varchar[]) AS approved_aliases
                    FROM complex_region
                    WHERE (:allSido OR complex_region.sido_code = :sidoCode)
                      AND complex_region.sido_code IS NOT NULL
                      AND complex_region.sigungu_code IS NOT NULL
                      AND complex_region.dong_code IS NOT NULL
                    ORDER BY complex_region.sido_code, complex_region.id
                    """)
                .param("allSido", sidoCode == null)
                .param("sidoCode", sidoCode)
                .query(this::mapComplexEvidence)
                .list();
    }

    private NewsComplexEvidence mapComplexEvidence(ResultSet rs, int rowNum) throws SQLException {
        return new NewsComplexEvidence(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("trade_name"),
                strings(rs.getArray("approved_aliases")),
                new NewsRegionEvidence(
                        rs.getString("sido_code"),
                        rs.getString("sido_name"),
                        rs.getString("sigungu_code"),
                        rs.getString("sigungu_name"),
                        rs.getString("dong_code"),
                        rs.getString("dong_name")),
                rs.getBoolean("duplicate_name"));
    }

    private List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record SidoRow(String code, String name) {}

    private record ScopeRow(MarketNewsScopeType type, String regionCode) {}

    private record ExecutionPublicationRow(String executionType, String policyVersion) {}

    private record ResumableExecutionRow(
            UUID executionId,
            String requestId,
            String executionType,
            String policyVersion,
            Instant scheduledAt,
            Instant overlapCutoff,
            int callBudget,
            int consumedCallCount,
            int plannedWorkUnitCount,
            int completedWorkUnitCount,
            int failedWorkUnitCount,
            int truncatedWorkUnitCount,
            int skippedBudgetWorkUnitCount,
            MarketNewsFailureKind stoppingFailureKind) {}

    private record ResumableWorkUnitRow(
            UUID workUnitId,
            int order,
            MarketNewsWorkUnitKind kind,
            MarketNewsScopeType scopeType,
            String regionCode,
            Long complexId,
            MarketNewsCategory category,
            String query,
            int lastProviderStart,
            int callCount,
            int rawItemCount,
            Instant oldestProvidedAt) {}
}
