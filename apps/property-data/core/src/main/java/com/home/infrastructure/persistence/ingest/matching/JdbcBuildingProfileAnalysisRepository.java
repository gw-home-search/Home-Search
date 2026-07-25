package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisComplex;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisRecord;
import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisRepository;
import com.home.application.ingest.buildingprofile.BuildingProfileAssignmentEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileComparisonEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileComplexMatchEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileFieldQualityEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileReportStats;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingprofile.BuildingProfileValueState;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfileAnalysisRepository implements BuildingProfileAnalysisRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingProfileAnalysisRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public boolean startOrLoad(BuildingProfileAnalysisCommand command) {
        return transaction.execute(ignored -> {
            String campaignStatus = jdbc.sql("""
                        SELECT status FROM building_register_collection_campaign WHERE collection_id=:collection
                        """)
                    .param("collection", command.collectionId())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("profile collection does not exist"));
            if (!"COMPLETED".equals(campaignStatus))
                throw new IllegalStateException("profile collection must be COMPLETED");
            ParseRun parseRun = jdbc.sql("""
                        SELECT source_collection_id,status FROM building_register_profile_parse_run
                        WHERE parse_run_id=:run
                        """)
                    .param("run", command.parseRunId())
                    .query((rs, rowNum) ->
                            new ParseRun(rs.getObject("source_collection_id", UUID.class), rs.getString("status")))
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("profile parse run does not exist"));
            if (!"COMPLETED".equals(parseRun.status()))
                throw new IllegalStateException("profile parse run must be COMPLETED");
            if (!command.collectionId().equals(parseRun.sourceCollectionId())) {
                throw new IllegalArgumentException("collectionId and parseRun source do not match");
            }
            jdbc.sql("""
                        INSERT INTO building_register_profile_analysis_run
                          (analysis_run_id,collection_id,parse_run_id,rules_version,status)
                        VALUES (:analysis,:collection,:parse,:rules,'RUNNING')
                        ON CONFLICT (analysis_run_id) DO NOTHING
                        """)
                    .param("analysis", command.analysisRunId())
                    .param("collection", command.collectionId())
                    .param("parse", command.parseRunId())
                    .param("rules", command.rulesVersion())
                    .update();
            AnalysisRun stored = jdbc.sql("""
                        SELECT collection_id,parse_run_id,rules_version,status
                        FROM building_register_profile_analysis_run
                        WHERE analysis_run_id=:analysis FOR UPDATE
                        """)
                    .param("analysis", command.analysisRunId())
                    .query((rs, rowNum) -> new AnalysisRun(
                            rs.getObject("collection_id", UUID.class),
                            rs.getObject("parse_run_id", UUID.class),
                            rs.getString("rules_version"),
                            rs.getString("status")))
                    .single();
            if (!stored.collectionId().equals(command.collectionId())
                    || !stored.parseRunId().equals(command.parseRunId())
                    || !stored.rulesVersion().equals(command.rulesVersion())) {
                throw new IllegalArgumentException("analysisRunId is already frozen with different inputs");
            }
            if ("FAILED".equals(stored.status())) throw new IllegalStateException("failed analysis run cannot resume");
            return "COMPLETED".equals(stored.status());
        });
    }

    @Override
    public List<BuildingProfileAnalysisRecord> recordsPage(UUID parseRunId, long afterRecordId, int limit) {
        if (afterRecordId < 0) throw new IllegalArgumentException("afterRecordId must be non-negative");
        if (limit <= 0 || limit > 5_000) throw new IllegalArgumentException("limit must be 1..5000");
        List<ValueRow> rows = jdbc.sql("""
                    WITH record_page AS MATERIALIZED (
                        SELECT id
                        FROM building_register_profile_record
                        WHERE parse_run_id=:run AND id>:after
                        ORDER BY id
                        LIMIT :limit
                    )
                    SELECT r.id,r.pnu,r.endpoint,r.mgm_bldrgst_pk,r.mgm_up_bldrgst_pk,r.regstr_kind_cd,
                           v.field_id,v.value_state,v.raw_value,v.text_value,v.decimal_value,v.integer_value,
                           v.date_value,v.boolean_value
                    FROM record_page page
                    JOIN building_register_profile_record r ON r.id=page.id
                    LEFT JOIN LATERAL (
                        SELECT value.field_id,value.value_state,value.raw_value,value.text_value,
                               value.decimal_value,value.integer_value,value.date_value,value.boolean_value
                        FROM building_register_profile_value value
                        WHERE value.profile_record_id=r.id
                        OFFSET 0
                    ) v ON true
                    ORDER BY r.id,v.field_id
                    """)
                .param("run", parseRunId)
                .param("after", afterRecordId)
                .param("limit", limit)
                .query(this::valueRow)
                .list();
        Map<Long, MutableRecord> records = new LinkedHashMap<>();
        for (ValueRow row : rows) {
            MutableRecord record = records.computeIfAbsent(
                    row.recordId(),
                    ignored -> new MutableRecord(
                            row.recordId(),
                            row.pnu(),
                            row.endpoint(),
                            row.managementKey(),
                            row.parentManagementKey(),
                            row.registerKindCode(),
                            new EnumMap<>(BuildingProfileField.class)));
            if (row.field() != null) record.values().put(row.field(), row.value());
        }
        return records.values().stream()
                .map(record -> new BuildingProfileAnalysisRecord(
                        record.recordId(),
                        record.pnu(),
                        record.endpoint(),
                        record.managementKey(),
                        record.parentManagementKey(),
                        record.registerKindCode(),
                        text(record.values().get(BuildingProfileField.NEW_OLD_REGSTR_GB_CD)),
                        record.values()))
                .toList();
    }

    @Override
    public List<BuildingProfileAnalysisComplex> complexes(UUID collectionId) {
        return jdbc.sql("""
                    SELECT target.complex_id,target.pnu,count(*) OVER (PARTITION BY target.pnu)::integer AS pnu_count
                    FROM building_register_collection_target target
                    WHERE target.collection_id=:collection
                    ORDER BY target.pnu,target.complex_id
                    """)
                .param("collection", collectionId)
                .query((rs, rowNum) -> new BuildingProfileAnalysisComplex(
                        rs.getLong("complex_id"), rs.getString("pnu"), rs.getInt("pnu_count")))
                .list();
    }

    @Override
    public Map<String, Double> sampleWeights(UUID collectionId) {
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.sql("""
                    SELECT pnu,sampling_weight FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection ORDER BY pnu
                    """)
                .param("collection", collectionId)
                .query((rs, rowNum) -> new Weight(rs.getString("pnu"), rs.getDouble("sampling_weight")))
                .list()
                .forEach(weight -> result.put(weight.pnu(), weight.value()));
        return Map.copyOf(result);
    }

    @Override
    public Map<String, String> sampleStrata(UUID collectionId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("""
                    SELECT pnu,stratum FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection ORDER BY pnu
                    """)
                .param("collection", collectionId)
                .query((rs, rowNum) -> new Stratum(rs.getString("pnu"), rs.getString("stratum")))
                .list()
                .forEach(value -> result.put(value.pnu(), value.stratum()));
        return Map.copyOf(result);
    }

    @Override
    public double operationalCompletion(UUID collectionId) {
        return jdbc.sql("""
                    SELECT CASE WHEN count(*)=0 THEN 0::double precision
                                ELSE count(*) FILTER (WHERE collection_status='COLLECTED')::double precision/count(*) END
                    FROM building_register_profile_sample_pnu WHERE collection_id=:collection
                    """)
                .param("collection", collectionId)
                .query(Double.class)
                .single();
    }

    @Override
    public Map<String, Double> operationalCompletionByStratum(UUID collectionId) {
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.sql("""
                    SELECT stratum,
                           count(*) FILTER (WHERE collection_status='COLLECTED')::double precision/count(*) AS completion
                    FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection
                    GROUP BY stratum ORDER BY stratum
                    """)
                .param("collection", collectionId)
                .query((rs, rowNum) -> new StratumCompletion(rs.getString("stratum"), rs.getDouble("completion")))
                .list()
                .forEach(value -> result.put(value.stratum(), value.completion()));
        return Map.copyOf(result);
    }

    @Override
    public BuildingProfileReportStats reportStats(UUID collectionId, UUID parseRunId) {
        Map<String, Long> endpointStatuses = countMap("""
                SELECT snapshot.endpoint || ':' || page.status AS key,count(*) AS count
                FROM building_register_profile_parse_page page
                JOIN building_register_raw_page raw ON raw.id=page.raw_page_id
                JOIN building_register_endpoint_snapshot snapshot ON snapshot.id=raw.endpoint_snapshot_id
                WHERE page.parse_run_id=:id
                GROUP BY snapshot.endpoint,page.status ORDER BY snapshot.endpoint,page.status
                """, parseRunId);
        Map<String, Long> valueStates = countMap("""
                SELECT field_id || ':' || value_state AS key,count(*) AS count
                FROM building_register_profile_value value
                JOIN building_register_profile_record record ON record.id=value.profile_record_id
                WHERE record.parse_run_id=:id GROUP BY field_id,value_state ORDER BY field_id,value_state
                """, parseRunId);
        Map<String, Long> transitions = countMap("""
                SELECT comparison_status AS key,count(*) AS count
                FROM building_register_profile_code_lookup
                WHERE collection_id=:id GROUP BY comparison_status ORDER BY comparison_status
                """, collectionId);
        long storageBytes = jdbc.sql("""
                    SELECT coalesce(sum(pg_total_relation_size(table_name::regclass)),0)
                    FROM unnest(ARRAY[
                      'building_register_profile_parse_run','building_register_profile_parse_page',
                      'building_register_profile_record','building_register_profile_value',
                      'building_register_profile_schema_observation','building_register_profile_scope_assignment',
                      'building_register_profile_complex_match','building_register_profile_comparison',
                      'building_register_profile_field_quality'
                    ]) AS table_name
                    """).query(Long.class).single();
        return new BuildingProfileReportStats(endpointStatuses, valueStates, transitions, storageBytes);
    }

    private Map<String, Long> countMap(String sql, UUID id) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("id", id)
                .query((rs, rowNum) -> new CountValue(rs.getString("key"), rs.getLong("count")))
                .list()
                .forEach(value -> result.put(value.key(), value.count()));
        return Map.copyOf(result);
    }

    @Override
    public void recordAssignments(UUID analysisRunId, List<BuildingProfileAssignmentEvidence> assignments) {
        transaction.executeWithoutResult(ignored -> assignments.forEach(value -> jdbc.sql("""
                    INSERT INTO building_register_profile_scope_assignment
                      (analysis_run_id,profile_record_id,root_management_key,scope_key,status,assignment_reason)
                    VALUES (:analysis,:record,:root,:scope,:status,:reason)
                    ON CONFLICT (analysis_run_id,profile_record_id) DO NOTHING
                    """)
                .param("analysis", analysisRunId)
                .param("record", value.profileRecordId())
                .param("root", value.rootManagementKey())
                .param("scope", value.scopeKey())
                .param("status", value.status().name())
                .param("reason", truncate(value.reason(), 80))
                .update()));
    }

    @Override
    public void recordComplexMatches(
            UUID analysisRunId, UUID collectionId, List<BuildingProfileComplexMatchEvidence> matches) {
        transaction.executeWithoutResult(ignored -> matches.forEach(value -> jdbc.sql("""
                    INSERT INTO building_register_profile_complex_match
                      (analysis_run_id,collection_id,complex_id,pnu,scope_key,status,projectable,failure_reason)
                    VALUES (:analysis,:collection,:complex,:pnu,:scope,:status,:projectable,:reason)
                    ON CONFLICT (analysis_run_id,complex_id) DO NOTHING
                    """)
                .param("analysis", analysisRunId)
                .param("collection", collectionId)
                .param("complex", value.complexId())
                .param("pnu", value.pnu())
                .param("scope", value.scopeKey())
                .param("status", value.status().name())
                .param("projectable", value.projectable())
                .param("reason", truncate(value.reason(), 200))
                .update()));
    }

    @Override
    public void recordComparisons(UUID analysisRunId, List<BuildingProfileComparisonEvidence> comparisons) {
        transaction.executeWithoutResult(ignored -> comparisons.forEach(value -> jdbc.sql("""
                    INSERT INTO building_register_profile_comparison
                      (analysis_run_id,pnu_scope_hash,field_id,aggregation_method,status,recap_value,title_value,
                       difference,contributor_count,expected_contributor_count)
                    VALUES (:analysis,:scope_hash,:field,:method,:status,
                            to_jsonb(CAST(:recap AS text)),to_jsonb(CAST(:title AS text)),
                            :difference,:contributors,:expected)
                    ON CONFLICT (analysis_run_id,pnu_scope_hash,field_id,aggregation_method) DO NOTHING
                    """)
                .param("analysis", analysisRunId)
                .param("scope_hash", value.scopeHash())
                .param("field", value.field().name())
                .param("method", value.aggregation().name())
                .param("status", value.status().name())
                .param("recap", value.recapValue())
                .param("title", value.titleValue())
                .param("difference", value.difference())
                .param("contributors", value.contributorCount())
                .param("expected", value.expectedContributorCount())
                .update()));
    }

    @Override
    public void recordFieldQuality(UUID analysisRunId, List<BuildingProfileFieldQualityEvidence> quality) {
        transaction.executeWithoutResult(ignored -> quality.forEach(value -> jdbc.sql("""
                    INSERT INTO building_register_profile_field_quality
                      (analysis_run_id,field_id,field_scope,stratum,source_record_coverage,building_coverage,
                       pnu_coverage,projectable_complex_readiness,operational_completion,invalid_rate,conflict_rate,
                       wilson_low,wilson_high,quality_tier,meaning_verified,numerator,denominator)
                    VALUES (:analysis,:field,:scope,:stratum,:source_coverage,:building_coverage,
                            :pnu_coverage,:readiness,:operational,:invalid_rate,:conflict_rate,
                            :wilson_low,:wilson_high,:tier,:meaning_verified,:numerator,:denominator)
                    ON CONFLICT (analysis_run_id,field_id,stratum) DO NOTHING
                    """)
                .param("analysis", analysisRunId)
                .param("field", value.field().name())
                .param("scope", value.field().scope().name())
                .param("stratum", value.stratum())
                .param("source_coverage", value.sourceRecordCoverage())
                .param("building_coverage", value.buildingCoverage())
                .param("pnu_coverage", value.pnuCoverage())
                .param("readiness", value.projectableComplexReadiness())
                .param("operational", value.operationalCompletion())
                .param("invalid_rate", value.invalidRate())
                .param("conflict_rate", value.conflictRate())
                .param("wilson_low", value.wilsonLow())
                .param("wilson_high", value.wilsonHigh())
                .param("tier", value.qualityTier().name())
                .param("meaning_verified", value.meaningVerified())
                .param("numerator", value.numerator())
                .param("denominator", value.denominator())
                .update()));
    }

    @Override
    public void complete(UUID analysisRunId, String reportManifestJson) {
        jdbc.sql("""
                    UPDATE building_register_profile_analysis_run
                    SET status='COMPLETED',completed_at=COALESCE(completed_at,now()),
                        report_manifest=CAST(:manifest AS jsonb),failure_reason=NULL
                    WHERE analysis_run_id=:analysis AND status IN ('RUNNING','COMPLETED')
                    """)
                .param("manifest", reportManifestJson)
                .param("analysis", analysisRunId)
                .update();
    }

    private ValueRow valueRow(ResultSet rs, int rowNum) throws SQLException {
        String fieldId = rs.getString("field_id");
        BuildingProfileField field = fieldId == null ? null : BuildingProfileField.valueOf(fieldId);
        BuildingProfileTypedValue value = field == null
                ? null
                : new BuildingProfileTypedValue(
                        BuildingProfileValueState.valueOf(rs.getString("value_state")),
                        rs.getString("raw_value"),
                        rs.getString("text_value"),
                        rs.getBigDecimal("decimal_value"),
                        rs.getObject("integer_value", Long.class),
                        rs.getObject("date_value", LocalDate.class),
                        rs.getObject("boolean_value", Boolean.class));
        return new ValueRow(
                rs.getLong("id"),
                rs.getString("pnu"),
                BuildingRegisterEndpoint.valueOf(rs.getString("endpoint")),
                rs.getString("mgm_bldrgst_pk"),
                rs.getString("mgm_up_bldrgst_pk"),
                integer(rs.getString("regstr_kind_cd")),
                field,
                value);
    }

    private int integer(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String text(BuildingProfileTypedValue value) {
        return value == null ? null : value.textValue();
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(value.length(), max));
    }

    private record ParseRun(UUID sourceCollectionId, String status) {}

    private record AnalysisRun(UUID collectionId, UUID parseRunId, String rulesVersion, String status) {}

    private record Weight(String pnu, double value) {}

    private record Stratum(String pnu, String stratum) {}

    private record StratumCompletion(String stratum, double completion) {}

    private record CountValue(String key, long count) {}

    private record ValueRow(
            long recordId,
            String pnu,
            BuildingRegisterEndpoint endpoint,
            String managementKey,
            String parentManagementKey,
            int registerKindCode,
            BuildingProfileField field,
            BuildingProfileTypedValue value) {}

    private record MutableRecord(
            long recordId,
            String pnu,
            BuildingRegisterEndpoint endpoint,
            String managementKey,
            String parentManagementKey,
            int registerKindCode,
            EnumMap<BuildingProfileField, BuildingProfileTypedValue> values) {}
}
