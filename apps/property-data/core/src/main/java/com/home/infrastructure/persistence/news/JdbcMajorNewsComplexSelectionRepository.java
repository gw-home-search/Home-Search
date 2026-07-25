package com.home.infrastructure.persistence.news;

import com.home.application.news.selection.MajorNewsComplexCandidate;
import com.home.application.news.selection.MajorNewsComplexSelectionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMajorNewsComplexSelectionRepository implements MajorNewsComplexSelectionRepository {

    private final JdbcClient jdbcClient;

    public JdbcMajorNewsComplexSelectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public boolean hasPublishedSelection(LocalDate selectionWeek) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM market_news_major_complex_selection
                        WHERE selection_week = :selectionWeek
                          AND selection_status = 'PUBLISHED'
                    )
                    """)
                .param("selectionWeek", selectionWeek)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<MajorNewsComplexCandidate> findCandidates(LocalDate asOfDate) {
        return jdbcClient
                .sql("""
                    WITH complex_region AS (
                        SELECT complex.id AS complex_id, complex.name AS complex_name, complex.unit_cnt,
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
                                   CASE WHEN region0.region_type = 'eup-myeon-dong' THEN region0.name END,
                                   CASE WHEN region1.region_type = 'eup-myeon-dong' THEN region1.name END,
                                   CASE WHEN region2.region_type = 'eup-myeon-dong' THEN region2.name END
                               ) AS dong_name
                        FROM complex
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                    )
                    SELECT complex_region.complex_id, complex_region.sido_code,
                           complex_region.sido_name, complex_region.sigungu_name,
                           complex_region.dong_name, complex_region.complex_name,
                           count(*)::integer AS trade_count_90d, complex_region.unit_cnt
                    FROM complex_region
                    JOIN trade ON trade.complex_id = complex_region.complex_id
                    WHERE trade.deleted_at IS NULL
                      AND trade.deal_date BETWEEN :fromDate AND :asOfDate
                      AND complex_region.sido_code IS NOT NULL
                      AND complex_region.sigungu_name IS NOT NULL
                      AND complex_region.dong_name IS NOT NULL
                      AND btrim(complex_region.complex_name) <> ''
                    GROUP BY complex_region.complex_id, complex_region.sido_code,
                             complex_region.sido_name, complex_region.sigungu_name,
                             complex_region.dong_name, complex_region.complex_name,
                             complex_region.unit_cnt
                    ORDER BY complex_region.sido_code,
                             trade_count_90d DESC,
                             complex_region.unit_cnt DESC NULLS LAST,
                             complex_region.complex_id
                    """)
                .param("fromDate", asOfDate.minusDays(89))
                .param("asOfDate", asOfDate)
                .query((rs, rowNum) -> new MajorNewsComplexCandidate(
                        rs.getLong("complex_id"),
                        rs.getString("sido_code"),
                        rs.getString("sido_name"),
                        rs.getString("sigungu_name"),
                        rs.getString("dong_name"),
                        rs.getString("complex_name"),
                        rs.getInt("trade_count_90d"),
                        rs.getObject("unit_cnt", Integer.class)))
                .list();
    }

    @Override
    @Transactional
    public void publish(LocalDate selectionWeek, List<MajorNewsComplexCandidate> selected) {
        int rank = 1;
        for (MajorNewsComplexCandidate candidate : selected) {
            jdbcClient
                    .sql("""
                        INSERT INTO market_news_major_complex_selection (
                            selection_week, rank, complex_id, region_code,
                            trade_count_90d, unit_cnt, selection_status
                        ) VALUES (
                            :selectionWeek, :rank, :complexId, :regionCode,
                            :tradeCount90d, :unitCnt, 'BUILDING'
                        )
                        """)
                    .param("selectionWeek", selectionWeek)
                    .param("rank", rank++)
                    .param("complexId", candidate.complexId())
                    .param("regionCode", candidate.sidoCode())
                    .param("tradeCount90d", candidate.tradeCount90d())
                    .param("unitCnt", candidate.unitCount())
                    .update();
        }
        jdbcClient.sql("""
                    UPDATE market_news_major_complex_selection
                    SET selection_status = 'PUBLISHED'
                    WHERE selection_week = :selectionWeek
                      AND selection_status = 'BUILDING'
                      AND (SELECT count(*) FROM market_news_major_complex_selection
                           WHERE selection_week = :selectionWeek
                             AND selection_status = 'BUILDING') = 200
                    """).param("selectionWeek", selectionWeek).update();
    }
}
