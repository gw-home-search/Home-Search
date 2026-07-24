package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRatioProjectionRepository;
import com.home.application.ingest.buildingregister.BuildingRatioProjectionTarget;
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
    public List<BuildingRatioProjectionTarget> findProjectionTargets(
            UUID collectionId, Long fromComplexId, Long toComplexId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return jdbc.sql("""
                    SELECT m.id AS match_id,field_scope.field,candidate.id AS candidate_id
                    FROM building_register_complex_match m
                    CROSS JOIN (VALUES ('BUILDING_COVERAGE_RATIO'),('FLOOR_AREA_RATIO')) field_scope(field)
                    LEFT JOIN LATERAL (
                        SELECT c.id
                        FROM building_ratio_candidate c
                        WHERE c.match_id=m.id AND c.field=field_scope.field
                          AND (c.selected OR c.status='SOURCE_CONFLICT')
                        ORDER BY c.selected DESC,c.id
                        LIMIT 1
                    ) candidate ON true
                    WHERE m.collection_id=:collection
                      AND m.complex_id>=COALESCE(:from_id,m.complex_id)
                      AND m.complex_id<=COALESCE(:to_id,m.complex_id)
                    ORDER BY m.complex_id,field_scope.field LIMIT :limit
                    """)
                .param("collection", collectionId)
                .param("from_id", fromComplexId)
                .param("to_id", toComplexId)
                .param("limit", limit)
                .query((resultSet, rowNum) -> new BuildingRatioProjectionTarget(
                        resultSet.getLong("match_id"),
                        BuildingRatioField.valueOf(resultSet.getString("field")),
                        resultSet.getObject("candidate_id", Long.class)))
                .list();
    }

    @Override
    public BuildingRatioProjectionOutcome project(UUID requestId, BuildingRatioProjectionTarget target) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(target, "target");
        return transaction.execute(status -> projectInTransaction(requestId, target));
    }

    private BuildingRatioProjectionOutcome projectInTransaction(UUID requestId, BuildingRatioProjectionTarget target) {
        BuildingRatioProjectionOutcome previous = jdbc.sql("""
                    SELECT outcome FROM building_ratio_projection
                    WHERE request_id=:request AND match_id=:match AND field=:field
                    """)
                .param("request", requestId)
                .param("match", target.matchId())
                .param("field", target.field().name())
                .query(String.class)
                .optional()
                .map(BuildingRatioProjectionOutcome::valueOf)
                .orElse(null);
        if (previous != null) return previous;

        if (target.candidateId() == null) {
            long complexId = jdbc.sql("SELECT complex_id FROM building_register_complex_match WHERE id=:match")
                    .param("match", target.matchId())
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("match not found: " + target.matchId()));
            recordProjection(
                    requestId, target, complexId, BuildingRatioProjectionOutcome.SOURCE_MISSING, null, null, null);
            return BuildingRatioProjectionOutcome.SOURCE_MISSING;
        }

        Candidate candidate = jdbc.sql("""
                    SELECT c.id,c.field,c.projected_value,c.status,c.selected,m.complex_id,m.scope,m.projectable
                    FROM building_ratio_candidate c
                    JOIN building_register_complex_match m ON m.id=c.match_id
                    WHERE c.id=:candidate AND c.match_id=:match AND c.field=:field
                    """)
                .param("candidate", target.candidateId())
                .param("match", target.matchId())
                .param("field", target.field().name())
                .query(this::candidate)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("candidate does not belong to projection target"));
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
        recordProjection(requestId, target, candidate.complexId(), outcome, current, candidate.value(), applied);
        return outcome;
    }

    private void recordProjection(
            UUID requestId,
            BuildingRatioProjectionTarget target,
            long complexId,
            BuildingRatioProjectionOutcome outcome,
            BigDecimal previous,
            BigDecimal source,
            BigDecimal applied) {
        jdbc.sql("""
                    INSERT INTO building_ratio_projection
                        (request_id,match_id,candidate_id,complex_id,field,outcome,
                         previous_value,source_value,applied_value)
                    VALUES (:request,:match,:candidate,:complex,:field,:outcome,:previous,:source,:applied)
                    """)
                .param("request", requestId)
                .param("match", target.matchId())
                .param("candidate", target.candidateId())
                .param("complex", complexId)
                .param("field", target.field().name())
                .param("outcome", outcome.name())
                .param("previous", previous)
                .param("source", source)
                .param("applied", applied)
                .update();
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
