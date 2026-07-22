package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfileParsedPage;
import com.home.application.ingest.buildingprofile.BuildingProfileParsedRecord;
import com.home.application.ingest.buildingprofile.BuildingProfileRawPage;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileReplayRepository;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileParseStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingprofile.BuildingProfileValueState;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfileReplayRepository implements BuildingProfileReplayRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingProfileReplayRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public void startOrResume(BuildingProfileReplayCommand command) {
        transaction.executeWithoutResult(ignored -> {
            jdbc.sql("""
                        INSERT INTO building_register_profile_parse_run
                          (parse_run_id,source_collection_id,parser_version,status)
                        VALUES (:run,:source,:version,'RUNNING')
                        ON CONFLICT (parse_run_id) DO NOTHING
                        """)
                    .param("run", command.parseRunId())
                    .param("source", command.sourceCollectionId())
                    .param("version", command.parserVersion())
                    .update();
            ParseRun stored = jdbc.sql("""
                        SELECT source_collection_id,parser_version,status
                        FROM building_register_profile_parse_run
                        WHERE parse_run_id=:run FOR UPDATE
                        """)
                    .param("run", command.parseRunId())
                    .query((rs, rowNum) -> new ParseRun(
                            rs.getObject("source_collection_id", UUID.class),
                            rs.getString("parser_version"),
                            rs.getString("status")))
                    .single();
            if (!stored.sourceCollectionId().equals(command.sourceCollectionId())
                    || !stored.parserVersion().equals(command.parserVersion())) {
                throw new IllegalArgumentException("parseRunId is already frozen with different inputs");
            }
            if ("FAILED".equals(stored.status())) throw new IllegalStateException("failed parse run cannot resume");
        });
    }

    @Override
    public List<BuildingProfileRawPage> nextPages(UUID parseRunId, UUID sourceCollectionId, int limit) {
        return jdbc.sql("""
                    SELECT p.id,p.status,p.provider_status,p.response_body,p.page_no,
                           s.endpoint,s.pnu,s.page_size
                    FROM building_register_raw_page p
                    JOIN building_register_endpoint_snapshot s ON s.id=p.endpoint_snapshot_id
                    WHERE s.collection_id=:source
                      AND NOT EXISTS (
                        SELECT 1 FROM building_register_profile_parse_page parsed
                        WHERE parsed.parse_run_id=:run AND parsed.raw_page_id=p.id
                      )
                    ORDER BY p.id
                    LIMIT :limit
                    """)
                .param("source", sourceCollectionId)
                .param("run", parseRunId)
                .param("limit", limit)
                .query(this::rawPage)
                .list();
    }

    @Override
    public void recordPage(UUID parseRunId, BuildingProfileRawPage rawPage, BuildingProfileParsedPage parsedPage) {
        transaction.executeWithoutResult(ignored -> {
            int inserted = jdbc.sql("""
                        INSERT INTO building_register_profile_parse_page
                          (parse_run_id,raw_page_id,status,provider_status,total_count,record_count)
                        VALUES (:run,:raw,:status,:provider,:total,:records)
                        ON CONFLICT (parse_run_id,raw_page_id) DO NOTHING
                        """)
                    .param("run", parseRunId)
                    .param("raw", rawPage.rawPageId())
                    .param("status", parsedPage.records().isEmpty() ? "EMPTY" : "PARSED")
                    .param("provider", parsedPage.resultCode())
                    .param("total", parsedPage.totalCount())
                    .param("records", parsedPage.records().size())
                    .update();
            if (inserted == 0) return;
            parsedPage.records().forEach(record -> {
                insertRecord(parseRunId, rawPage.rawPageId(), record);
                observeValueIssues(parseRunId, rawPage, record);
            });
            parsedPage.unknownKeys().forEach(key -> observeUnknown(parseRunId, rawPage, key));
        });
    }

    @Override
    public void recordFailure(
            UUID parseRunId, BuildingProfileRawPage rawPage, BuildingProfileParseStatus status, String failureCode) {
        if (status != BuildingProfileParseStatus.PROVIDER_FAILED && status != BuildingProfileParseStatus.PARSE_FAILED) {
            throw new IllegalArgumentException("failure status is required");
        }
        String safeCode = safeFailureCode(failureCode);
        transaction.executeWithoutResult(ignored -> {
            int inserted = jdbc.sql("""
                        INSERT INTO building_register_profile_parse_page
                          (parse_run_id,raw_page_id,status,provider_status,record_count,failure_reason)
                        VALUES (:run,:raw,:status,:provider,0,:failure)
                        ON CONFLICT (parse_run_id,raw_page_id) DO NOTHING
                        """)
                    .param("run", parseRunId)
                    .param("raw", rawPage.rawPageId())
                    .param("status", status.name())
                    .param("provider", rawPage.providerStatus())
                    .param("failure", safeCode)
                    .update();
            if (inserted == 1 && status == BuildingProfileParseStatus.PARSE_FAILED) {
                jdbc.sql("""
                            INSERT INTO building_register_profile_schema_observation
                              (parse_run_id,raw_page_id,endpoint,observation_type,occurrence_count)
                            VALUES (:run,:raw,:endpoint,'PARSE_ERROR',1)
                            ON CONFLICT DO NOTHING
                            """)
                        .param("run", parseRunId)
                        .param("raw", rawPage.rawPageId())
                        .param("endpoint", rawPage.endpoint().name())
                        .update();
            }
        });
    }

    @Override
    public boolean completeIfAllPagesProcessed(UUID parseRunId, UUID sourceCollectionId) {
        return transaction.execute(status -> {
            int remaining = jdbc.sql("""
                        SELECT count(*)
                        FROM building_register_raw_page p
                        JOIN building_register_endpoint_snapshot s ON s.id=p.endpoint_snapshot_id
                        WHERE s.collection_id=:source
                          AND NOT EXISTS (
                            SELECT 1 FROM building_register_profile_parse_page parsed
                            WHERE parsed.parse_run_id=:run AND parsed.raw_page_id=p.id
                          )
                        """)
                    .param("source", sourceCollectionId)
                    .param("run", parseRunId)
                    .query(Integer.class)
                    .single();
            if (remaining != 0) return false;
            jdbc.sql("""
                        UPDATE building_register_profile_parse_run run
                        SET status='COMPLETED',completed_at=COALESCE(completed_at,now()),
                            page_count=(SELECT count(*) FROM building_register_profile_parse_page p
                                        WHERE p.parse_run_id=run.parse_run_id),
                            record_count=(SELECT count(*) FROM building_register_profile_record r
                                          WHERE r.parse_run_id=run.parse_run_id)
                        WHERE parse_run_id=:run AND status IN ('RUNNING','COMPLETED')
                        """).param("run", parseRunId).update();
            return true;
        });
    }

    private void insertRecord(UUID parseRunId, long rawPageId, BuildingProfileParsedRecord record) {
        Long recordId = jdbc.sql("""
                    INSERT INTO building_register_profile_record
                      (parse_run_id,raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,regstr_kind_cd)
                    VALUES (:run,:raw,:item,:pnu,:endpoint,:key,:parent,:kind)
                    ON CONFLICT (parse_run_id,raw_page_id,item_index) DO NOTHING
                    RETURNING id
                    """)
                .param("run", parseRunId)
                .param("raw", rawPageId)
                .param("item", record.itemIndex())
                .param("pnu", record.pnu())
                .param("endpoint", record.endpoint().name())
                .param("key", text(record, BuildingProfileField.MGM_BLDRGST_PK))
                .param("parent", text(record, BuildingProfileField.MGM_UP_BLDRGST_PK))
                .param("kind", text(record, BuildingProfileField.REGSTR_KIND_CD))
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                            SELECT id FROM building_register_profile_record
                            WHERE parse_run_id=:run AND raw_page_id=:raw AND item_index=:item
                            """)
                        .param("run", parseRunId)
                        .param("raw", rawPageId)
                        .param("item", record.itemIndex())
                        .query(Long.class)
                        .single());
        record.values().forEach((field, value) -> insertValue(recordId, field, value));
    }

    private void insertValue(long recordId, BuildingProfileField field, BuildingProfileTypedValue value) {
        jdbc.sql("""
                    INSERT INTO building_register_profile_value
                      (profile_record_id,field_id,field_scope,aggregation_method,zero_policy,
                       value_type,value_state,raw_value,text_value,decimal_value,integer_value,date_value,boolean_value)
                    VALUES (:record,:field,:scope,:aggregation,:zero_policy,
                            :type,:state,:raw,:text,:decimal,:integer,:date,:boolean)
                    ON CONFLICT (profile_record_id,field_id) DO NOTHING
                    """)
                .param("record", recordId)
                .param("field", field.name())
                .param("scope", field.scope().name())
                .param("aggregation", field.aggregation().name())
                .param("zero_policy", field.zeroPolicy().name())
                .param("type", field.valueType().name())
                .param("state", value.state().name())
                .param("raw", value.rawValue())
                .param("text", value.textValue())
                .param("decimal", value.decimalValue())
                .param("integer", value.integerValue())
                .param("date", value.dateValue())
                .param("boolean", value.booleanValue())
                .update();
    }

    private void observeUnknown(UUID parseRunId, BuildingProfileRawPage rawPage, String key) {
        jdbc.sql("""
                    INSERT INTO building_register_profile_schema_observation
                      (parse_run_id,raw_page_id,endpoint,observation_type,source_key,occurrence_count)
                    VALUES (:run,:raw,:endpoint,'UNKNOWN_KEY',:key,1)
                    ON CONFLICT (parse_run_id,raw_page_id,endpoint,observation_type,source_key,field_id,observed_type)
                    DO UPDATE SET occurrence_count=building_register_profile_schema_observation.occurrence_count+1
                    """)
                .param("run", parseRunId)
                .param("raw", rawPage.rawPageId())
                .param("endpoint", rawPage.endpoint().name())
                .param("key", key)
                .update();
    }

    private void observeValueIssues(
            UUID parseRunId, BuildingProfileRawPage rawPage, BuildingProfileParsedRecord record) {
        record.values().forEach((field, value) -> {
            if (value.state() == BuildingProfileValueState.INVALID) {
                observe(
                        parseRunId,
                        rawPage,
                        "TYPE_DRIFT",
                        null,
                        field.name(),
                        field.valueType().name(),
                        value.rawValue() == null ? null : sha256(value.rawValue()));
            }
        });
        BuildingProfileTypedValue managementKey = record.value(BuildingProfileField.MGM_BLDRGST_PK);
        if (managementKey == null || !managementKey.state().hasTypedValue()) {
            observe(
                    parseRunId,
                    rawPage,
                    "MISSING_REQUIRED",
                    null,
                    BuildingProfileField.MGM_BLDRGST_PK.name(),
                    null,
                    null);
        }
    }

    private void observe(
            UUID parseRunId,
            BuildingProfileRawPage rawPage,
            String observationType,
            String sourceKey,
            String fieldId,
            String observedType,
            String sampleHash) {
        jdbc.sql("""
                    INSERT INTO building_register_profile_schema_observation
                      (parse_run_id,raw_page_id,endpoint,observation_type,source_key,field_id,
                       observed_type,occurrence_count,sample_value_sha256)
                    VALUES (:run,:raw,:endpoint,:type,:source_key,:field,:observed_type,1,:sample_hash)
                    ON CONFLICT (parse_run_id,raw_page_id,endpoint,observation_type,source_key,field_id,observed_type)
                    DO UPDATE SET occurrence_count=building_register_profile_schema_observation.occurrence_count+1
                    """)
                .param("run", parseRunId)
                .param("raw", rawPage.rawPageId())
                .param("endpoint", rawPage.endpoint().name())
                .param("type", observationType)
                .param("source_key", sourceKey)
                .param("field", fieldId)
                .param("observed_type", observedType)
                .param("sample_hash", sampleHash)
                .update();
    }

    private String sha256(String value) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte current : MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BuildingProfileRawPage rawPage(ResultSet rs, int rowNum) throws SQLException {
        return new BuildingProfileRawPage(
                rs.getLong("id"),
                BuildingRegisterEndpoint.valueOf(rs.getString("endpoint")),
                rs.getString("pnu"),
                rs.getInt("page_no"),
                rs.getInt("page_size"),
                BuildingRegisterRawPageStatus.valueOf(rs.getString("status")),
                rs.getString("provider_status"),
                rs.getString("response_body"));
    }

    private String text(BuildingProfileParsedRecord record, BuildingProfileField field) {
        BuildingProfileTypedValue value = record.value(field);
        return value == null ? null : value.textValue();
    }

    private String safeFailureCode(String value) {
        if (value == null || value.isBlank()) return "PROFILE_FAILURE";
        String normalized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 80));
    }

    private record ParseRun(UUID sourceCollectionId, String parserVersion, String status) {}
}
