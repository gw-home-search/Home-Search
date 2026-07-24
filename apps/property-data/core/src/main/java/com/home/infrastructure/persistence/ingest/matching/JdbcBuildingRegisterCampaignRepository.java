package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRegisterCampaignCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignRepository;
import com.home.application.ingest.buildingregister.BuildingRegisterCampaignTarget;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingRegisterCampaignRepository implements BuildingRegisterCampaignRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingRegisterCampaignRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public List<BuildingRegisterCampaignTarget> freezeOrLoad(BuildingRegisterCampaignCommand command) {
        transaction.executeWithoutResult(status -> freeze(command));
        List<TargetRow> rows = jdbc.sql("""
                    SELECT t.complex_id,t.pnu,t.initial_bld_mgm_bld_rgst_pk,c.name
                    FROM building_register_collection_target t
                    JOIN complex c ON c.id=t.complex_id
                    WHERE t.collection_id=:collection ORDER BY t.target_ordinal
                    """)
                .param("collection", command.collectionId())
                .query(this::targetRow)
                .list();
        Map<Long, Set<String>> aliases = evidence(command.collectionId(), """
                SELECT alias.complex_id,alias.alias_name AS value
                FROM building_register_collection_target target
                JOIN complex_name_alias alias ON alias.complex_id=target.complex_id
                WHERE target.collection_id=:collection
                ORDER BY target.target_ordinal,alias.alias_name
                """);
        Map<Long, Set<String>> tradeDongs = evidence(command.collectionId(), """
                SELECT DISTINCT target.complex_id,trade.apt_dong AS value
                FROM building_register_collection_target target
                JOIN trade ON trade.complex_id=target.complex_id
                WHERE target.collection_id=:collection
                  AND trade.apt_dong IS NOT NULL AND btrim(trade.apt_dong)<>''
                ORDER BY target.complex_id,trade.apt_dong
                """);
        Map<Long, Set<String>> footprintDongs = evidence(command.collectionId(), """
                SELECT DISTINCT target.complex_id,footprint.dong_name AS value
                FROM building_register_collection_target target
                JOIN complex_building_link link ON link.complex_id=target.complex_id AND link.status='RESOLVED'
                JOIN building_footprint_snapshot footprint ON footprint.id=link.building_footprint_id
                WHERE target.collection_id=:collection
                  AND footprint.dong_name IS NOT NULL AND btrim(footprint.dong_name)<>''
                ORDER BY target.complex_id,footprint.dong_name
                """);
        List<BuildingRegisterCampaignTarget> targets = new ArrayList<>();
        for (TargetRow row : rows) {
            Set<String> names = new LinkedHashSet<>();
            if (row.name() != null && !row.name().isBlank()) names.add(row.name());
            names.addAll(aliases.getOrDefault(row.complexId(), Set.of()));
            targets.add(new BuildingRegisterCampaignTarget(
                    row.complexId(),
                    row.pnu(),
                    row.existingKey(),
                    names,
                    tradeDongs.getOrDefault(row.complexId(), Set.of()),
                    footprintDongs.getOrDefault(row.complexId(), Set.of())));
        }
        return List.copyOf(targets);
    }

    private Map<Long, Set<String>> evidence(UUID collectionId, String sql) {
        Map<Long, Set<String>> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .param("collection", collectionId)
                .query((resultSet, rowNumber) ->
                        new EvidenceRow(resultSet.getLong("complex_id"), resultSet.getString("value")))
                .list()
                .forEach(row -> result.computeIfAbsent(row.complexId(), ignored -> new LinkedHashSet<>())
                        .add(row.value()));
        return result;
    }

    private void freeze(BuildingRegisterCampaignCommand command) {
        Campaign existing = jdbc.sql("""
                    SELECT mode,strategy,from_complex_id,to_complex_id
                    FROM building_register_collection_campaign WHERE collection_id=:collection FOR UPDATE
                    """)
                .param("collection", command.collectionId())
                .query((resultSet, rowNum) -> new Campaign(
                        resultSet.getString("mode"),
                        resultSet.getString("strategy"),
                        resultSet.getObject("from_complex_id", Long.class),
                        resultSet.getLong("to_complex_id")))
                .optional()
                .orElse(null);
        if (existing == null) {
            jdbc.sql("""
                        INSERT INTO building_register_collection_campaign
                            (collection_id,mode,strategy,from_complex_id,to_complex_id,status)
                        VALUES (:collection,:mode,:strategy,:from_id,:to_id,'COLLECTING')
                        """)
                    .param("collection", command.collectionId())
                    .param("mode", command.mode().storedValue())
                    .param("strategy", command.strategy().name())
                    .param("from_id", command.fromComplexId())
                    .param("to_id", command.toComplexId())
                    .update();
            jdbc.sql("""
                        WITH eligible AS (
                            SELECT c.id,c.bc_rat,c.vl_rat,c.bld_mgm_bld_rgst_pk,p.pnu,
                                   row_number() OVER (ORDER BY c.id) AS ordinal
                            FROM complex c JOIN parcel p ON p.id=c.parcel_id
                            WHERE (c.bc_rat IS NULL OR c.vl_rat IS NULL)
                              AND c.id>=COALESCE(:from_id,c.id) AND c.id<=:to_id
                        )
                        INSERT INTO building_register_collection_target
                            (collection_id,target_ordinal,complex_id,pnu,initial_bc_rat,initial_vl_rat,
                             initial_bld_mgm_bld_rgst_pk)
                        SELECT :collection,ordinal,id,pnu,bc_rat,vl_rat,bld_mgm_bld_rgst_pk FROM eligible
                        """)
                    .param("collection", command.collectionId())
                    .param("from_id", command.fromComplexId())
                    .param("to_id", command.toComplexId())
                    .update();
            return;
        }
        if (!existing.mode().equals(command.mode().storedValue())
                || !existing.strategy().equals(command.strategy().name())
                || !Objects.equals(existing.fromId(), command.fromComplexId())
                || existing.toId() != command.toComplexId()) {
            throw new IllegalArgumentException("collectionId is already frozen with different campaign parameters");
        }
    }

    @Override
    public boolean isCompleted(UUID collectionId) {
        return jdbc.sql("""
                    SELECT status='COMPLETED'
                    FROM building_register_collection_campaign
                    WHERE collection_id=:collection
                    """)
                .param("collection", collectionId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Set<String> fullyMatchedPnus(UUID collectionId) {
        return Set.copyOf(jdbc.sql("""
                    SELECT target.pnu
                    FROM building_register_collection_target target
                    LEFT JOIN building_register_complex_match match
                      ON match.collection_id=target.collection_id
                     AND match.complex_id=target.complex_id
                    WHERE target.collection_id=:collection
                    GROUP BY target.pnu
                    HAVING count(*)=count(match.id)
                    """)
                .param("collection", collectionId)
                .query(String.class)
                .list());
    }

    @Override
    public long recordMatch(UUID collectionId, String pnu, int pnuComplexCount, BuildingRegisterComplexMatch match) {
        long matchId = jdbc.sql("""
                    INSERT INTO building_register_complex_match
                        (collection_id,complex_id,pnu,root_management_key,scope,status,match_path,projectable,failure_reason)
                    VALUES (:collection,:complex,:pnu,:root,:scope,:status,:path,:projectable,:reason)
                    ON CONFLICT (collection_id,complex_id) DO UPDATE SET
                        root_management_key=EXCLUDED.root_management_key,scope=EXCLUDED.scope,
                        status=EXCLUDED.status,match_path=EXCLUDED.match_path,
                        projectable=EXCLUDED.projectable,failure_reason=EXCLUDED.failure_reason,
                        evaluated_at=now()
                    RETURNING id
                    """)
                .param("collection", collectionId)
                .param("complex", match.complexId())
                .param("pnu", pnu)
                .param("root", match.rootManagementKey())
                .param("scope", match.scope().name())
                .param("status", match.status().name())
                .param("path", match.path() == null ? null : match.path().name())
                .param("projectable", match.projectable())
                .param("reason", match.reason())
                .query(Long.class)
                .single();
        recordEvidence(matchId, "PNU_CARDINALITY", "MATCH", "pnuComplexCount", pnuComplexCount);
        if (match.path() != null) {
            String type =
                    switch (match.path()) {
                        case EXISTING_KEY -> "EXISTING_KEY";
                        case UNIQUE_PNU -> "PNU_CARDINALITY";
                        case EXACT_NAME -> "EXACT_NAME";
                        case EXACT_DONG_SET -> "DONG_SET";
                        case FOOTPRINT_EVIDENCE -> "FOOTPRINT_LINK";
                    };
            recordEvidence(matchId, type, "MATCH", "matchPath", match.path().name());
        }
        return matchId;
    }

    private void recordEvidence(long matchId, String type, String outcome, String key, Object value) {
        jdbc.sql("""
                    INSERT INTO building_register_match_evidence(match_id,evidence_type,outcome,evidence_value)
                    SELECT :match,:type,:outcome,jsonb_build_object(:key,CAST(:value AS text))
                    WHERE NOT EXISTS (
                        SELECT 1 FROM building_register_match_evidence
                        WHERE match_id=:match AND evidence_type=:type
                    )
                    """)
                .param("match", matchId)
                .param("type", type)
                .param("outcome", outcome)
                .param("key", key)
                .param("value", value)
                .update();
    }

    @Override
    public Map<String, Long> sourceRecordIds(UUID collectionId, String pnu) {
        List<SourceRecord> rows = jdbc.sql("""
                    SELECT r.mgm_bldrgst_pk,r.id
                    FROM building_register_record_snapshot r
                    JOIN building_register_raw_page p ON p.id=r.raw_page_id
                    JOIN building_register_endpoint_snapshot s ON s.id=p.endpoint_snapshot_id
                    WHERE s.collection_id=:collection AND s.pnu=:pnu
                      AND p.status='PARSED' AND r.endpoint IN ('RECAP_TITLE','TITLE')
                    ORDER BY CASE r.endpoint WHEN 'RECAP_TITLE' THEN 0 ELSE 1 END,r.id DESC
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .query((resultSet, rowNum) ->
                        new SourceRecord(resultSet.getString("mgm_bldrgst_pk"), resultSet.getLong("id")))
                .list();
        Map<String, Long> result = new LinkedHashMap<>();
        rows.forEach(row -> result.putIfAbsent(row.key(), row.id()));
        return Map.copyOf(result);
    }

    @Override
    public boolean completeIfAllTargetsMatched(UUID collectionId) {
        return transaction.execute(status -> {
            int remaining = jdbc.sql("""
                        SELECT count(*)
                        FROM building_register_collection_target t
                        WHERE t.collection_id=:collection AND NOT EXISTS (
                            SELECT 1 FROM building_register_complex_match m
                            WHERE m.collection_id=t.collection_id AND m.complex_id=t.complex_id
                        )
                        """)
                    .param("collection", collectionId)
                    .query(Integer.class)
                    .single();
            if (remaining != 0) return false;
            jdbc.sql("""
                        UPDATE building_register_collection_campaign
                        SET status='COMPLETED',completed_at=COALESCE(completed_at,now())
                        WHERE collection_id=:collection AND status IN ('CREATED','COLLECTING','COMPLETED')
                        """).param("collection", collectionId).update();
            return true;
        });
    }

    private TargetRow targetRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new TargetRow(
                resultSet.getLong("complex_id"),
                resultSet.getString("pnu"),
                resultSet.getString("initial_bld_mgm_bld_rgst_pk"),
                resultSet.getString("name"));
    }

    private record Campaign(String mode, String strategy, Long fromId, long toId) {}

    private record TargetRow(long complexId, String pnu, String existingKey, String name) {}

    private record EvidenceRow(long complexId, String value) {}

    private record SourceRecord(String key, long id) {}
}
