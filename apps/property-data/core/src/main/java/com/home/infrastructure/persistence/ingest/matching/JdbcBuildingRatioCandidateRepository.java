package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRatioCandidateRepository;
import com.home.application.ingest.buildingregister.BuildingRatioRecordedEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioCandidate;
import com.home.domain.complex.buildingregister.BuildingRatioEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioField;
import com.home.domain.complex.buildingregister.BuildingRatioFieldEvaluation;
import com.home.domain.complex.buildingregister.BuildingRatioResolutionMethod;
import com.home.domain.complex.buildingregister.BuildingRatioResolutionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingRatioCandidateRepository implements BuildingRatioCandidateRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingRatioCandidateRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public BuildingRatioRecordedEvaluation record(
            long matchId, BuildingRatioEvaluation evaluation, Map<String, Long> recordIdsByManagementKey) {
        if (matchId <= 0) throw new IllegalArgumentException("matchId must be positive");
        Objects.requireNonNull(evaluation, "evaluation");
        Map<String, Long> inputs = recordIdsByManagementKey == null ? Map.of() : Map.copyOf(recordIdsByManagementKey);
        return transaction.execute(status -> recordInTransaction(matchId, evaluation, inputs));
    }

    private BuildingRatioRecordedEvaluation recordInTransaction(
            long matchId, BuildingRatioEvaluation evaluation, Map<String, Long> inputs) {
        EnumMap<BuildingRatioField, Long> selected = new EnumMap<>(BuildingRatioField.class);
        for (BuildingRatioField field : BuildingRatioField.values()) {
            BuildingRatioFieldEvaluation fieldEvaluation = evaluation.field(field);
            resetEvaluation(matchId, field);
            for (BuildingRatioCandidate candidate : fieldEvaluation.candidates()) {
                boolean isSelected = candidate.equals(fieldEvaluation.selectedCandidate());
                long candidateId = findOrInsert(matchId, fieldEvaluation, candidate, isSelected);
                recordInputs(candidateId, candidate, inputs);
                if (isSelected) selected.put(field, candidateId);
            }
        }
        return new BuildingRatioRecordedEvaluation(selected);
    }

    private void resetEvaluation(long matchId, BuildingRatioField field) {
        jdbc.sql("""
                    UPDATE building_ratio_candidate
                    SET selected=false,status='INCOMPLETE',reason='superseded by reevaluation'
                    WHERE match_id=:match AND field=:field
                    """).param("match", matchId).param("field", field.name()).update();
    }

    private long findOrInsert(
            long matchId, BuildingRatioFieldEvaluation evaluation, BuildingRatioCandidate candidate, boolean selected) {
        BigDecimal storedValue = candidate.value().setScale(8, RoundingMode.HALF_UP);
        BigDecimal storedNumerator = storedComponent(candidate.numerator());
        BigDecimal storedDenominator = storedComponent(candidate.denominator());
        Long existing = jdbc.sql("""
                    SELECT id FROM building_ratio_candidate
                    WHERE match_id=:match AND field=:field AND method=:method AND value=:value
                      AND numerator IS NOT DISTINCT FROM :numerator
                      AND denominator IS NOT DISTINCT FROM :denominator
                    ORDER BY id LIMIT 1
                    """)
                .param("match", matchId)
                .param("field", candidate.field().name())
                .param("method", candidate.method().name())
                .param("value", storedValue)
                .param("numerator", storedNumerator)
                .param("denominator", storedDenominator)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existing != null) {
            jdbc.sql("""
                        UPDATE building_ratio_candidate
                        SET status=:status,selected=:selected,reason=:reason
                        WHERE id=:id
                        """)
                    .param("status", storedStatus(evaluation.status()))
                    .param("selected", selected)
                    .param("reason", reason(evaluation.status()))
                    .param("id", existing)
                    .update();
            return existing;
        }
        return jdbc.sql("""
                    INSERT INTO building_ratio_candidate
                        (match_id,field,method,value,projected_value,numerator,denominator,status,selected,reason)
                    VALUES (:match,:field,:method,:value,:projected,:numerator,:denominator,:status,:selected,:reason)
                    RETURNING id
                    """)
                .param("match", matchId)
                .param("field", candidate.field().name())
                .param("method", candidate.method().name())
                .param("value", storedValue)
                .param("projected", candidate.projectedValue())
                .param("numerator", storedNumerator)
                .param("denominator", storedDenominator)
                .param("status", storedStatus(evaluation.status()))
                .param("selected", selected)
                .param("reason", reason(evaluation.status()))
                .query(Long.class)
                .single();
    }

    private String storedStatus(BuildingRatioResolutionStatus status) {
        return status == BuildingRatioResolutionStatus.SOURCE_CONFLICT ? "SOURCE_CONFLICT" : "VALID";
    }

    private BigDecimal storedComponent(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private void recordInputs(long candidateId, BuildingRatioCandidate candidate, Map<String, Long> inputs) {
        for (String key : candidate.inputManagementKeys()) {
            Long recordId = inputs.get(key);
            if (recordId == null) throw new IllegalArgumentException("source record id is missing for " + key);
            for (String role : inputRoles(candidate.method(), recordId)) {
                jdbc.sql("""
                            INSERT INTO building_ratio_candidate_input(candidate_id,record_snapshot_id,input_role)
                            VALUES (:candidate,:record,:role) ON CONFLICT DO NOTHING
                            """)
                        .param("candidate", candidateId)
                        .param("record", recordId)
                        .param("role", role)
                        .update();
            }
        }
    }

    private List<String> inputRoles(BuildingRatioResolutionMethod method, long recordId) {
        return switch (method) {
            case RECAP_DIRECT, TITLE_DIRECT_CONSENSUS, STANDALONE_TITLE_DIRECT -> List.of("DIRECT");
            case RECAP_COMPONENT_CALC, TITLE_AGGREGATE_CALC, STANDALONE_TITLE_COMPONENT_CALC ->
                List.of("NUMERATOR", "DENOMINATOR");
            case RECAP_NUMERATOR_TITLE_DENOMINATOR -> List.of(isRecap(recordId) ? "NUMERATOR" : "DENOMINATOR");
        };
    }

    private boolean isRecap(long recordId) {
        return "1"
                .equals(jdbc.sql("SELECT regstr_kind_cd FROM building_register_record_snapshot WHERE id=:id")
                        .param("id", recordId)
                        .query(String.class)
                        .single());
    }

    private String reason(BuildingRatioResolutionStatus status) {
        return switch (status) {
            case SOURCE_CONFLICT -> "candidate projected values differ by more than 0.01";
            case SKIPPED_SHARED_SCOPE -> "shared recap scope is not projectable";
            case SELECTED, SOURCE_MISSING -> null;
        };
    }
}
