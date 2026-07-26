package com.home.infrastructure.persistence.news;

import com.home.application.news.read.MarketNewsCursor;
import com.home.application.news.read.MarketNewsItemView;
import com.home.application.news.read.MarketNewsReadRepository;
import com.home.application.news.read.MarketNewsReadResult;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsDataStatus;
import com.home.domain.news.MarketNewsRelationType;
import com.home.domain.news.MarketNewsScopeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketNewsReadRepository implements MarketNewsReadRepository {

    private static final Duration FRESH_AGE = Duration.ofHours(8);
    private final JdbcClient jdbcClient;
    private final Clock clock;

    @Autowired
    public JdbcMarketNewsReadRepository(JdbcClient jdbcClient) {
        this(jdbcClient, Clock.systemUTC());
    }

    JdbcMarketNewsReadRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean existsRootSidoCode(String regionCode) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM region
                        WHERE code = :regionCode
                          AND parent_id IS NULL
                          AND region_type = 'si-do'
                    )
                    """)
                .param("regionCode", regionCode)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean existsComplex(long complexId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("SELECT EXISTS (SELECT 1 FROM complex WHERE id = :complexId)")
                .param("complexId", complexId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public Optional<MarketNewsReadResult> findPublished(
            MarketNewsScopeType scopeType,
            String regionCode,
            MarketNewsCategory category,
            MarketNewsCursor cursor,
            int limit) {
        Optional<SnapshotRow> snapshot = jdbcClient
                .sql("""
                    SELECT snapshot.snapshot_id, snapshot.generated_at, snapshot.data_cutoff,
                           snapshot.build_status,
                           EXISTS (
                               SELECT 1
                               FROM market_news_collection_execution execution
                               JOIN market_news_collection_work_unit unit
                                 ON unit.execution_id = execution.execution_id
                               WHERE execution.created_at > snapshot.generated_at
                                 AND execution.execution_type IN ('GENERAL', 'MAJOR_COMPLEX', 'BOOTSTRAP')
                                 AND unit.state <> 'COMPLETED'
                                 AND unit.scope_type = snapshot.scope_type
                                 AND ((snapshot.region_code IS NULL AND unit.region_code IS NULL)
                                      OR unit.region_code = snapshot.region_code)
                           ) AS newer_incomplete
                    FROM market_news_snapshot snapshot
                    WHERE (
                          (CAST(:cursorSnapshotId AS uuid) IS NULL
                           AND snapshot.build_status IN ('PUBLISHED', 'SUPERSEDED'))
                          OR
                          (snapshot.snapshot_id = CAST(:cursorSnapshotId AS uuid)
                           AND snapshot.build_status IN ('PUBLISHED', 'SUPERSEDED'))
                      )
                      AND snapshot.scope_type = :scopeType
                      AND ((CAST(:regionCode AS varchar) IS NULL AND snapshot.region_code IS NULL)
                           OR snapshot.region_code = :regionCode)
                    ORDER BY CASE snapshot.build_status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                             snapshot.generated_at DESC
                    LIMIT 1
                    """)
                .param("scopeType", scopeType.name())
                .param("regionCode", regionCode)
                .param("cursorSnapshotId", cursor == null ? null : cursor.snapshotId())
                .query((rs, rowNum) -> new SnapshotRow(
                        rs.getObject("snapshot_id", UUID.class),
                        instant(rs, "generated_at"),
                        instant(rs, "data_cutoff"),
                        rs.getString("build_status"),
                        rs.getBoolean("newer_incomplete")))
                .optional();
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow row = snapshot.get();
        List<RankedMarketNewsItem> fetched = findSnapshotItems(row.snapshotId(), category, cursor, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<RankedMarketNewsItem> page = hasMore ? new ArrayList<>(fetched.subList(0, limit)) : fetched;
        RankedMarketNewsItem last = page.isEmpty() ? null : page.get(page.size() - 1);
        List<MarketNewsItemView> items =
                page.stream().map(RankedMarketNewsItem::item).toList();
        String nextCursor =
                hasMore && last != null ? new MarketNewsCursor(row.snapshotId(), last.sortRank()).encode() : null;
        MarketNewsDataStatus status = "PUBLISHED".equals(row.buildStatus())
                        && !row.newerIncomplete()
                        && Duration.between(row.generatedAt(), clock.instant()).compareTo(FRESH_AGE) <= 0
                ? MarketNewsDataStatus.FRESH
                : MarketNewsDataStatus.STALE;
        return Optional.of(new MarketNewsReadResult(
                row.snapshotId(),
                row.generatedAt(),
                row.dataCutoff(),
                status,
                scopeType,
                regionCode,
                category,
                List.copyOf(items),
                nextCursor));
    }

    @Override
    public List<MarketNewsItemView> findComplexNews(long complexId, int limit) {
        return jdbcClient
                .sql("""
                    WITH readable_snapshot AS (
                        SELECT DISTINCT ON (scope_type, COALESCE(region_code, ''))
                               snapshot_id
                        FROM market_news_snapshot
                        WHERE build_status IN ('PUBLISHED', 'SUPERSEDED')
                        ORDER BY scope_type, COALESCE(region_code, ''),
                                 CASE build_status WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                                 generated_at DESC
                    ), suppressed_geographic_direct AS (
                        SELECT DISTINCT direct.article_id
                        FROM market_news_relation direct
                        JOIN complex direct_target ON direct_target.id = direct.complex_id
                        LEFT JOIN region direct_region0 ON direct_region0.id = direct_target.region_id
                        LEFT JOIN region direct_region1 ON direct_region1.id = direct_region0.parent_id
                        LEFT JOIN region direct_region2 ON direct_region2.id = direct_region1.parent_id
                        CROSS JOIN LATERAL (VALUES
                            (direct_region0.name),
                            (direct_region1.name),
                            (direct_region2.name)
                        ) geographic(name)
                        WHERE direct.complex_id = :complexId
                          AND direct.relation_type = 'DIRECT_COMPLEX'
                          AND geographic.name IS NOT NULL
                          AND (
                              regexp_replace(
                                  lower(direct.matched_tokens[1]),
                                  '[^0-9a-z가-힣]+',
                                  '',
                                  'g'
                              ) = regexp_replace(
                                  lower(geographic.name),
                                  '[^0-9a-z가-힣]+',
                                  '',
                                  'g'
                              )
                              OR
                              regexp_replace(
                                  lower(direct.matched_tokens[1]),
                                  '[^0-9a-z가-힣]+',
                                  '',
                                  'g'
                              ) = regexp_replace(
                                  lower(regexp_replace(
                                      geographic.name,
                                      '(특별자치도|특별자치시|특별시|광역시|-myeon|-dong|-gun|-eup|-do|-si|-gu|-ri|도|시|군|구|동|읍|면|리)$',
                                      '',
                                      'i'
                                  )),
                                  '[^0-9a-z가-힣]+',
                                  '',
                                  'g'
                              )
                          )
                    ), candidate AS (
                    SELECT article.article_id, relation.category, article.title,
                           article.provided_at, article.public_url,
                           relation.region_code, region.name AS region_name, relation.relation_type,
                           relation.relation_id, item.provider_rank, 1 AS relation_priority
                    FROM market_news_relation relation
                    JOIN market_news_article article ON article.article_id = relation.article_id
                    JOIN market_news_snapshot_item item ON item.relation_id = relation.relation_id
                    JOIN readable_snapshot snapshot ON snapshot.snapshot_id = item.snapshot_id
                    LEFT JOIN region ON region.code = relation.region_code
                    WHERE relation.complex_id = :complexId
                      AND relation.relation_type = 'DIRECT_COMPLEX'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM suppressed_geographic_direct suppressed
                          WHERE suppressed.article_id = relation.article_id
                      )
                      AND article.provided_at >= :retentionCutoff
                    UNION ALL
                    SELECT article.article_id, relation.category, article.title,
                           article.provided_at, article.public_url,
                           relation.region_code, region.name AS region_name, relation.relation_type,
                           relation.relation_id, item.provider_rank,
                           CASE relation.relation_type WHEN 'SAME_DONG' THEN 2 ELSE 3 END AS relation_priority
                    FROM market_news_relation relation
                    JOIN market_news_article article ON article.article_id = relation.article_id
                    JOIN market_news_snapshot_item item ON item.relation_id = relation.relation_id
                    JOIN readable_snapshot snapshot ON snapshot.snapshot_id = item.snapshot_id
                    LEFT JOIN region ON region.code = relation.region_code
                    JOIN complex target ON target.id = :complexId
                    LEFT JOIN region target_region0 ON target_region0.id = target.region_id
                    LEFT JOIN region target_region1 ON target_region1.id = target_region0.parent_id
                    LEFT JOIN region target_region2 ON target_region2.id = target_region1.parent_id
                    WHERE relation.complex_id IS NULL
                      AND (
                          (relation.relation_type = 'SAME_DONG'
                           AND relation.region_code = COALESCE(
                               CASE WHEN target_region0.region_type = 'eup-myeon-dong'
                                    THEN target_region0.code END,
                               CASE WHEN target_region1.region_type = 'eup-myeon-dong'
                                    THEN target_region1.code END,
                               CASE WHEN target_region2.region_type = 'eup-myeon-dong'
                                    THEN target_region2.code END))
                          OR
                          (relation.relation_type = 'SAME_SIGUNGU'
                           AND relation.region_code = COALESCE(
                               CASE WHEN target_region0.region_type = 'si-gun-gu'
                                    THEN target_region0.code END,
                               CASE WHEN target_region1.region_type = 'si-gun-gu'
                                    THEN target_region1.code END,
                               CASE WHEN target_region2.region_type = 'si-gun-gu'
                                    THEN target_region2.code END,
                               CASE WHEN target_region0.region_type = 'si-do'
                                         AND target_region0.code = '36'
                                    THEN target_region0.code END,
                               CASE WHEN target_region1.region_type = 'si-do'
                                         AND target_region1.code = '36'
                                    THEN target_region1.code END,
                               CASE WHEN target_region2.region_type = 'si-do'
                                         AND target_region2.code = '36'
                                    THEN target_region2.code END))
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM suppressed_geographic_direct suppressed
                          WHERE suppressed.article_id = relation.article_id
                      )
                      AND article.provided_at >= :retentionCutoff
                    ), deduplicated AS (
                        SELECT DISTINCT ON (article_id)
                               article_id, category, title, provided_at, public_url,
                               region_code, region_name, relation_type,
                               provider_rank, relation_priority
                        FROM candidate
                        ORDER BY article_id, relation_priority, provider_rank, relation_id
                    )
                    SELECT article_id, category, title, provided_at, public_url,
                           region_code, region_name, relation_type
                    FROM deduplicated
                    ORDER BY relation_priority, provided_at DESC, provider_rank, article_id
                    LIMIT :limit
                    """)
                .param("complexId", complexId)
                .param("retentionCutoff", utc(clock.instant().minus(Duration.ofDays(30))))
                .param("limit", limit)
                .query(this::mapComplexItem)
                .list();
    }

    private List<RankedMarketNewsItem> findSnapshotItems(
            UUID snapshotId, MarketNewsCategory category, MarketNewsCursor cursor, int limit) {
        return jdbcClient
                .sql("""
                    SELECT item.sort_rank, article.article_id, item.category, article.title,
                           article.provided_at, article.public_url,
                           relation.region_code, region.name AS region_name
                    FROM market_news_snapshot_item item
                    JOIN market_news_article article ON article.article_id = item.article_id
                    JOIN market_news_relation relation ON relation.relation_id = item.relation_id
                    LEFT JOIN region ON region.code = relation.region_code
                    WHERE item.snapshot_id = :snapshotId
                      AND (:allCategories OR item.category = :category)
                      AND article.provided_at >= :retentionCutoff
                      AND item.sort_rank > :cursorSortRank
                    ORDER BY item.sort_rank
                    LIMIT :limit
                    """)
                .param("snapshotId", snapshotId)
                .param("allCategories", category == MarketNewsCategory.ALL)
                .param("category", category.name())
                .param("retentionCutoff", utc(clock.instant().minus(Duration.ofDays(30))))
                .param("cursorSortRank", cursor == null ? 0 : cursor.sortRank())
                .param("limit", limit)
                .query((rs, rowNum) -> new RankedMarketNewsItem(rs.getInt("sort_rank"), mapHubItem(rs, rowNum)))
                .list();
    }

    private MarketNewsItemView mapHubItem(ResultSet rs, int rowNum) throws SQLException {
        return new MarketNewsItemView(
                rs.getLong("article_id"),
                MarketNewsCategory.valueOf(rs.getString("category")),
                rs.getString("title"),
                instant(rs, "provided_at"),
                rs.getString("public_url"),
                rs.getString("region_code"),
                rs.getString("region_name"),
                null);
    }

    private MarketNewsItemView mapComplexItem(ResultSet rs, int rowNum) throws SQLException {
        return new MarketNewsItemView(
                rs.getLong("article_id"),
                MarketNewsCategory.valueOf(rs.getString("category")),
                rs.getString("title"),
                instant(rs, "provided_at"),
                rs.getString("public_url"),
                rs.getString("region_code"),
                rs.getString("region_name"),
                MarketNewsRelationType.valueOf(rs.getString("relation_type")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record SnapshotRow(
            UUID snapshotId, Instant generatedAt, Instant dataCutoff, String buildStatus, boolean newerIncomplete) {}

    private record RankedMarketNewsItem(int sortRank, MarketNewsItemView item) {}
}
