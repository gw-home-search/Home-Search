package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRatioProjectionRepository;
import com.home.domain.complex.buildingregister.BuildingRatioField;
import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import com.home.domain.complex.buildingregister.BuildingRatioScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingRatioProjectionRepository implements BuildingRatioProjectionRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingRatioProjectionRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public boolean isCampaignCompleted(UUID collectionId) {
        return jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM building_register_collection_campaign
                        WHERE collection_id=:collection AND status='COMPLETED'
                    )
                    """)
                .param("collection", collectionId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<Long> findSelectedCandidateIds(UUID collectionId, Long fromComplexId, Long toComplexId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return jdbc.sql("""
                    SELECT c.id
                    FROM building_ratio_candidate c
                    JOIN building_register_complex_match m ON m.id=c.match_id
                    WHERE m.collection_id=:collection AND (
                        c.selected OR (
                            c.status='SOURCE_CONFLICT' AND c.id=(
                                SELECT min(conflict.id) FROM building_ratio_candidate conflict
                                WHERE conflict.match_id=c.match_id AND conflict.field=c.field
                                  AND conflict.status='SOURCE_CONFLICT'
                            )
                        )
                      )
                      AND m.complex_id>=COALESCE(:from_id,m.complex_id)
                      AND m.complex_id<=COALESCE(:to_id,m.complex_id)
                    ORDER BY m.complex_id,c.field LIMIT :limit
                    """)
                .param("collection", collectionId)
                .param("from_id", fromComplexId)
                .param("to_id", toComplexId)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    @Override
    public BuildingRatioProjectionOutcome project(UUID requestId, long candidateId) {
        Objects.requireNonNull(requestId, "requestId");
        if (candidateId <= 0) throw new IllegalArgumentException("candidateId must be positive");
        return transaction.execute(status -> projectInTransaction(requestId, candidateId));
    }

    private BuildingRatioProjectionOutcome projectInTransaction(UUID requestId, long candidateId) {
        BuildingRatioProjectionOutcome previous = jdbc.sql("""
                    SELECT outcome FROM building_ratio_projection
                    WHERE request_id=:request AND candidate_id=:candidate
                    """)
                .param("request", requestId)
                .param("candidate", candidateId)
                .query(String.class)
                .optional()
                .map(BuildingRatioProjectionOutcome::valueOf)
                .orElse(null);
        if (previous != null) return previous;

        Candidate candidate = jdbc.sql("""
                    SELECT c.id,c.field,c.projected_value,c.status,c.selected,m.complex_id,m.scope,m.projectable
                    FROM building_ratio_candidate c
                    JOIN building_register_complex_match m ON m.id=c.match_id
                    WHERE c.id=:candidate
                    """)
                .param("candidate", candidateId)
                .query(this::candidate)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("candidate not found: " + candidateId));
        if (!candidate.selected() && !"SOURCE_CONFLICT".equals(candidate.status())) {
            throw new IllegalStateException("only selected or source-conflict candidate can be projected");
        }

        BigDecimal current = jdbc.sql("SELECT " + column(candidate.field()) + " FROM complex WHERE id=:id FOR UPDATE")
                .param("id", candidate.complexId())
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
        BuildingRatioProjectionOutcome outcome;
        BigDecimal applied = null;
        if ("SOURCE_CONFLICT".equals(candidate.status())) {
            outcome = BuildingRatioProjectionOutcome.SKIPPED_SOURCE_CONFLICT;
        } else if (!candidate.projectable() || candidate.scope() == BuildingRatioScope.SHARED_RECAP) {
            outcome = BuildingRatioProjectionOutcome.SKIPPED_SHARED_SCOPE;
        } else if (current == null) {
            int updated = jdbc.sql("UPDATE complex SET " + column(candidate.field())
                            + "=:value,updated_at=now() WHERE id=:id AND " + column(candidate.field()) + " IS NULL")
                    .param("value", candidate.value())
                    .param("id", candidate.complexId())
                    .update();
            if (updated != 1) throw new IllegalStateException("complex ratio changed while locked");
            outcome = BuildingRatioProjectionOutcome.APPLIED;
            applied = candidate.value();
        } else if (current.compareTo(candidate.value()) == 0) {
            outcome = BuildingRatioProjectionOutcome.ALREADY_EQUAL;
        } else {
            outcome = BuildingRatioProjectionOutcome.SKIPPED_EXISTING_CONFLICT;
        }
        jdbc.sql("""
                    INSERT INTO building_ratio_projection
                        (request_id,candidate_id,complex_id,field,outcome,previous_value,source_value,applied_value)
                    VALUES (:request,:candidate,:complex,:field,:outcome,:previous,:source,:applied)
                    """)
                .param("request", requestId)
                .param("candidate", candidate.id())
                .param("complex", candidate.complexId())
                .param("field", candidate.field().name())
                .param("outcome", outcome.name())
                .param("previous", current)
                .param("source", candidate.value())
                .param("applied", applied)
                .update();
        return outcome;
    }

    private String column(BuildingRatioField field) {
        return switch (field) {
            case BUILDING_COVERAGE_RATIO -> "bc_rat";
            case FLOOR_AREA_RATIO -> "vl_rat";
        };
    }

    private Candidate candidate(ResultSet resultSet, int rowNum) throws SQLException {
        return new Candidate(
                resultSet.getLong("id"),
                BuildingRatioField.valueOf(resultSet.getString("field")),
                resultSet.getBigDecimal("projected_value"),
                resultSet.getString("status"),
                resultSet.getBoolean("selected"),
                resultSet.getLong("complex_id"),
                BuildingRatioScope.valueOf(resultSet.getString("scope")),
                resultSet.getBoolean("projectable"));
    }

    private record Candidate(
            long id,
            BuildingRatioField field,
            BigDecimal value,
            String status,
            boolean selected,
            long complexId,
            BuildingRatioScope scope,
            boolean projectable) {}
}
