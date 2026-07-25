package com.home.infrastructure.persistence.news;

import com.home.application.news.quality.MarketNewsQualitySampleResult;
import com.home.application.news.quality.MarketNewsQualitySamplingRepository;
import com.home.domain.news.MarketNewsQualityReviewStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMarketNewsQualitySamplingRepository implements MarketNewsQualitySamplingRepository {

    private final JdbcClient jdbcClient;

    public JdbcMarketNewsQualitySamplingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public MarketNewsQualitySampleResult createDeterministicSample(UUID reviewSetId, String policyVersion) {
        jdbcClient
                .sql("""
                    INSERT INTO market_news_quality_review_set (
                        review_set_id, policy_version, status, total_sample_count,
                        minimum_category_count, covered_sido_count, direct_complex_count,
                        same_dong_count, same_sigungu_count, complex_challenge_count,
                        url_sample_count
                    ) VALUES (
                        :reviewSetId, :policyVersion, 'INSUFFICIENT_SAMPLE', 0, 0, 0, 0, 0, 0, 0, 0
                    )
                    ON CONFLICT (review_set_id) DO NOTHING
                    """)
                .param("reviewSetId", reviewSetId)
                .param("policyVersion", policyVersion)
                .update();
        String storedPolicyVersion = jdbcClient
                .sql("""
                    SELECT policy_version
                    FROM market_news_quality_review_set
                    WHERE review_set_id = :reviewSetId
                    FOR UPDATE
                    """)
                .param("reviewSetId", reviewSetId)
                .query(String.class)
                .single();
        if (!policyVersion.equals(storedPolicyVersion)) {
            throw new IllegalStateException("reviewSetId is already bound to another policyVersion");
        }
        captureSourceSnapshots(reviewSetId, policyVersion);

        jdbcClient
                .sql("""
                    WITH name_catalog AS (
                        SELECT id AS complex_id,
                               regexp_replace(lower(name), '[^0-9a-z가-힣]', '', 'g') AS normalized_name
                        FROM complex
                        UNION ALL
                        SELECT id,
                               regexp_replace(lower(trade_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex
                        WHERE trade_name IS NOT NULL AND btrim(trade_name) <> ''
                        UNION ALL
                        SELECT complex_id,
                               regexp_replace(lower(alias_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex_name_alias
                        WHERE alias_type = 'ADMIN_ALIAS' AND btrim(alias_name) <> ''
                    ), duplicate_names AS (
                        SELECT normalized_name
                        FROM name_catalog
                        WHERE normalized_name <> ''
                        GROUP BY normalized_name
                        HAVING count(DISTINCT complex_id) > 1
                    ), challenge_complexes AS (
                        SELECT DISTINCT catalog.complex_id
                        FROM name_catalog catalog
                        LEFT JOIN duplicate_names USING (normalized_name)
                        WHERE catalog.normalized_name <> ''
                          AND (char_length(catalog.normalized_name) <= 4
                               OR duplicate_names.normalized_name IS NOT NULL)
                    ), candidates AS (
                        SELECT DISTINCT ON (item.article_id, item.relation_id)
                               item.article_id,
                               item.relation_id,
                               relation.category,
                               relation.relation_type,
                               relation.region_code,
                               relation.complex_id,
                               challenge.complex_id IS NOT NULL AS challenge_complex
                        FROM market_news_quality_review_snapshot review_snapshot
                        JOIN market_news_snapshot snapshot USING (snapshot_id)
                        JOIN market_news_snapshot_item item USING (snapshot_id)
                        JOIN market_news_relation relation USING (relation_id)
                        LEFT JOIN challenge_complexes challenge ON challenge.complex_id = relation.complex_id
                        WHERE review_snapshot.review_set_id = :reviewSetId
                          AND snapshot.policy_version = :policyVersion
                          AND relation.policy_version = :policyVersion
                        ORDER BY item.article_id, item.relation_id,
                                 (snapshot.scope_type = 'SIDO') DESC,
                                 snapshot.generated_at DESC,
                                 snapshot.snapshot_id
                    ), ranked AS (
                        SELECT candidates.*,
                               row_number() OVER (
                                   PARTITION BY category
                                   ORDER BY md5(CAST(:reviewSetId AS text) || ':category:' || relation_id),
                                            relation_id
                               ) AS category_rank,
                               row_number() OVER (
                                   PARTITION BY left(region_code, 2)
                                   ORDER BY md5(CAST(:reviewSetId AS text) || ':sido:' || relation_id),
                                            relation_id
                               ) AS sido_rank,
                               row_number() OVER (
                                   PARTITION BY relation_type
                                   ORDER BY md5(CAST(:reviewSetId AS text) || ':relation:' || relation_id),
                                            relation_id
                               ) AS relation_rank,
                               row_number() OVER (
                                   ORDER BY md5(CAST(:reviewSetId AS text) || ':url:' || relation_id),
                                            relation_id
                               ) AS url_rank,
                               row_number() OVER (
                                   PARTITION BY (
                                       relation_type = 'DIRECT_COMPLEX'
                                       AND challenge_complex
                                   )
                                   ORDER BY md5(CAST(:reviewSetId AS text) || ':challenge:' || relation_id),
                                            relation_id
                               ) AS challenge_rank
                        FROM candidates
                    ), selected AS (
                        SELECT article_id, relation_id, 'COMPLEX_CHALLENGE' AS sample_stratum, 1 AS priority
                        FROM ranked
                        WHERE relation_type = 'DIRECT_COMPLEX'
                          AND challenge_complex
                          AND challenge_rank <= 50
                        UNION ALL
                        SELECT article_id, relation_id, 'DIRECT_COMPLEX', 2
                        FROM ranked
                        WHERE relation_type = 'DIRECT_COMPLEX' AND relation_rank <= 60
                        UNION ALL
                        SELECT article_id, relation_id, 'SAME_DONG', 3
                        FROM ranked
                        WHERE relation_type = 'SAME_DONG' AND relation_rank <= 40
                        UNION ALL
                        SELECT article_id, relation_id, 'SAME_SIGUNGU', 4
                        FROM ranked
                        WHERE relation_type = 'SAME_SIGUNGU' AND relation_rank <= 40
                        UNION ALL
                        SELECT article_id, relation_id, 'SIDO_COVERAGE', 5
                        FROM ranked
                        WHERE region_code IS NOT NULL AND sido_rank <= 5
                        UNION ALL
                        SELECT article_id, relation_id, 'CATEGORY', 6
                        FROM ranked
                        WHERE category_rank <= 30
                        UNION ALL
                        SELECT article_id, relation_id, 'URL_OPEN', 7
                        FROM ranked
                        WHERE url_rank <= 100
                    ), deduplicated AS (
                        SELECT DISTINCT ON (article_id, relation_id)
                               article_id, relation_id, sample_stratum
                        FROM selected
                        ORDER BY article_id, relation_id, priority
                    )
                    INSERT INTO market_news_quality_label (
                        review_set_id, article_id, relation_id, sample_stratum
                    )
                    SELECT :reviewSetId, article_id, relation_id, sample_stratum
                    FROM deduplicated
                    ON CONFLICT (review_set_id, article_id, relation_id) DO NOTHING
                    """)
                .param("reviewSetId", reviewSetId)
                .param("policyVersion", policyVersion)
                .update();

        Coverage coverage = coverage(reviewSetId);
        MarketNewsQualityReviewStatus status = coverage.meetsMinimums()
                ? MarketNewsQualityReviewStatus.READY
                : MarketNewsQualityReviewStatus.INSUFFICIENT_SAMPLE;
        jdbcClient
                .sql("""
                    UPDATE market_news_quality_review_set
                    SET status = :status,
                        total_sample_count = :totalSampleCount,
                        minimum_category_count = :minimumCategoryCount,
                        covered_sido_count = :coveredSidoCount,
                        direct_complex_count = :directComplexCount,
                        same_dong_count = :sameDongCount,
                        same_sigungu_count = :sameSigunguCount,
                        complex_challenge_count = :complexChallengeCount,
                        url_sample_count = :urlSampleCount
                    WHERE review_set_id = :reviewSetId
                    """)
                .param("status", status.name())
                .param("totalSampleCount", coverage.totalSampleCount())
                .param("minimumCategoryCount", coverage.minimumCategoryCount())
                .param("coveredSidoCount", coverage.coveredSidoCount())
                .param("directComplexCount", coverage.directComplexCount())
                .param("sameDongCount", coverage.sameDongCount())
                .param("sameSigunguCount", coverage.sameSigunguCount())
                .param("complexChallengeCount", coverage.complexChallengeCount())
                .param("urlSampleCount", coverage.urlSampleCount())
                .param("reviewSetId", reviewSetId)
                .update();
        return coverage.toResult(reviewSetId, status);
    }

    private void captureSourceSnapshots(UUID reviewSetId, String policyVersion) {
        Boolean captured = jdbcClient
                .sql("""
                    SELECT source_snapshot_captured_at IS NOT NULL
                    FROM market_news_quality_review_set
                    WHERE review_set_id = :reviewSetId
                    """)
                .param("reviewSetId", reviewSetId)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(captured)) {
            return;
        }
        int snapshotCount = jdbcClient
                .sql("""
                    INSERT INTO market_news_quality_review_snapshot (
                        review_set_id, snapshot_id, scope_type, region_code
                    )
                    SELECT :reviewSetId, snapshot_id, scope_type, region_code
                    FROM market_news_snapshot
                    WHERE build_status = 'PUBLISHED'
                      AND policy_version = :policyVersion
                    ORDER BY scope_type, region_code NULLS FIRST
                    ON CONFLICT DO NOTHING
                    """)
                .param("reviewSetId", reviewSetId)
                .param("policyVersion", policyVersion)
                .update();
        jdbcClient
                .sql("""
                    UPDATE market_news_quality_review_set
                    SET source_snapshot_captured_at = now(),
                        source_snapshot_count = :snapshotCount
                    WHERE review_set_id = :reviewSetId
                      AND source_snapshot_captured_at IS NULL
                    """)
                .param("snapshotCount", snapshotCount)
                .param("reviewSetId", reviewSetId)
                .update();
    }

    private Coverage coverage(UUID reviewSetId) {
        return jdbcClient
                .sql("""
                    WITH categories(category) AS (
                        VALUES ('POLICY'), ('FINANCE_LOAN'), ('SUPPLY_SALE'),
                               ('REDEVELOPMENT'), ('TRANSACTION_PRICE'), ('TRANSPORT_DEVELOPMENT')
                    ), name_catalog AS (
                        SELECT id AS complex_id,
                               regexp_replace(lower(name), '[^0-9a-z가-힣]', '', 'g') AS normalized_name
                        FROM complex
                        UNION ALL
                        SELECT id,
                               regexp_replace(lower(trade_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex
                        WHERE trade_name IS NOT NULL AND btrim(trade_name) <> ''
                        UNION ALL
                        SELECT complex_id,
                               regexp_replace(lower(alias_name), '[^0-9a-z가-힣]', '', 'g')
                        FROM complex_name_alias
                        WHERE alias_type = 'ADMIN_ALIAS' AND btrim(alias_name) <> ''
                    ), duplicated_names AS (
                        SELECT normalized_name
                        FROM name_catalog
                        WHERE normalized_name <> ''
                        GROUP BY normalized_name
                        HAVING count(DISTINCT complex_id) > 1
                    ), challenge_complexes AS (
                        SELECT DISTINCT catalog.complex_id
                        FROM name_catalog catalog
                        LEFT JOIN duplicated_names USING (normalized_name)
                        WHERE catalog.normalized_name <> ''
                          AND (char_length(catalog.normalized_name) <= 4
                               OR duplicated_names.normalized_name IS NOT NULL)
                    ), labels AS (
                        SELECT label.article_id,
                               label.relation_id,
                               relation.category,
                               relation.relation_type,
                               relation.region_code,
                               relation.complex_id,
                               challenge.complex_id IS NOT NULL AS challenge_complex
                        FROM market_news_quality_label label
                        JOIN market_news_relation relation USING (relation_id)
                        LEFT JOIN challenge_complexes challenge ON challenge.complex_id = relation.complex_id
                        WHERE label.review_set_id = :reviewSetId
                    ), category_counts AS (
                        SELECT categories.category, count(labels.relation_id) AS sample_count
                        FROM categories
                        LEFT JOIN labels ON labels.category = categories.category
                        GROUP BY categories.category
                    ), sido_counts AS (
                        SELECT left(region_code, 2) AS sido_code, count(*) AS sample_count
                        FROM labels
                        WHERE region_code IS NOT NULL
                        GROUP BY left(region_code, 2)
                    )
                    SELECT
                        (SELECT count(*) FROM labels) AS total_sample_count,
                        (SELECT min(sample_count) FROM category_counts) AS minimum_category_count,
                        (SELECT count(*) FROM sido_counts WHERE sample_count >= 5) AS covered_sido_count,
                        count(*) FILTER (WHERE relation_type = 'DIRECT_COMPLEX') AS direct_complex_count,
                        count(*) FILTER (WHERE relation_type = 'SAME_DONG') AS same_dong_count,
                        count(*) FILTER (WHERE relation_type = 'SAME_SIGUNGU') AS same_sigungu_count,
                        count(*) FILTER (
                            WHERE relation_type = 'DIRECT_COMPLEX'
                              AND challenge_complex
                        ) AS complex_challenge_count,
                        least((SELECT count(*) FROM labels), 100) AS url_sample_count
                    FROM labels
                    """)
                .param("reviewSetId", reviewSetId)
                .query(this::mapCoverage)
                .single();
    }

    private Coverage mapCoverage(ResultSet rs, int rowNum) throws SQLException {
        return new Coverage(
                rs.getInt("total_sample_count"),
                rs.getInt("minimum_category_count"),
                rs.getInt("covered_sido_count"),
                rs.getInt("direct_complex_count"),
                rs.getInt("same_dong_count"),
                rs.getInt("same_sigungu_count"),
                rs.getInt("complex_challenge_count"),
                rs.getInt("url_sample_count"));
    }

    private record Coverage(
            int totalSampleCount,
            int minimumCategoryCount,
            int coveredSidoCount,
            int directComplexCount,
            int sameDongCount,
            int sameSigunguCount,
            int complexChallengeCount,
            int urlSampleCount) {

        private boolean meetsMinimums() {
            return minimumCategoryCount >= 30
                    && coveredSidoCount == 17
                    && directComplexCount >= 60
                    && sameDongCount >= 40
                    && sameSigunguCount >= 40
                    && complexChallengeCount >= 50
                    && urlSampleCount >= 100;
        }

        private MarketNewsQualitySampleResult toResult(UUID reviewSetId, MarketNewsQualityReviewStatus status) {
            return new MarketNewsQualitySampleResult(
                    reviewSetId,
                    status,
                    totalSampleCount,
                    minimumCategoryCount,
                    coveredSidoCount,
                    directComplexCount,
                    sameDongCount,
                    sameSigunguCount,
                    complexChallengeCount,
                    urlSampleCount);
        }
    }
}
