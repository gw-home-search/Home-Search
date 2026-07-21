package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfileCodeLookupEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileCodeTransition;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectTarget;
import com.home.application.ingest.buildingprofile.BuildingProfileSampleRepository;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
import com.home.domain.complex.buildingprofile.BuildingProfileSampleCandidate;
import com.home.domain.complex.buildingprofile.BuildingProfileSampleSelection;
import com.home.domain.complex.buildingprofile.BuildingProfileSampler;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfileSampleRepository implements BuildingProfileSampleRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final BuildingProfileSampler sampler = new BuildingProfileSampler();

    public JdbcBuildingProfileSampleRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public List<BuildingProfileCollectTarget> freezeOrLoad(BuildingProfileCollectCommand command) {
        transaction.executeWithoutResult(ignored -> freeze(command));
        return jdbc.sql("""
                    SELECT pnu,complex_count
                    FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection
                    ORDER BY stratum,seed_rank,pnu
                    """)
                .param("collection", command.collectionId())
                .query((rs, rowNum) ->
                        new BuildingProfileCollectTarget(rs.getString("pnu"), rs.getInt("complex_count")))
                .list();
    }

    private void freeze(BuildingProfileCollectCommand command) {
        Campaign existing = jdbc.sql("""
                    SELECT mode,strategy,selection_seed,sample_size
                    FROM building_register_collection_campaign
                    WHERE collection_id=:collection FOR UPDATE
                    """)
                .param("collection", command.collectionId())
                .query((rs, rowNum) -> new Campaign(
                        rs.getString("mode"), rs.getString("strategy"),
                        rs.getString("selection_seed"), rs.getInt("sample_size")))
                .optional()
                .orElse(null);
        if (existing != null) {
            if (!"profile".equals(existing.mode())
                    || !"COMPARE_RECAP_TITLE".equals(existing.strategy())
                    || !command.selectionSeed().equals(existing.selectionSeed())
                    || command.sampleSize() != existing.sampleSize()) {
                throw new IllegalArgumentException("collectionId is already frozen with different profile inputs");
            }
            return;
        }

        List<BuildingProfileSampleCandidate> candidates = candidates();
        BuildingProfileSampleSelection selection =
                sampler.select(candidates, command.sampleSize(), command.selectionSeed());
        long maxComplexId = jdbc.sql("SELECT COALESCE(max(id),0) FROM complex")
                .query(Long.class)
                .single();
        if (maxComplexId <= 0) throw new IllegalStateException("profile population has no complex rows");
        jdbc.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES (:collection,'profile','COMPARE_RECAP_TITLE',:to_id,'COLLECTING',
                            'PROFILE_DISCOVERY','VALIDATION_SAMPLE',:seed,:sample_size)
                    """)
                .param("collection", command.collectionId())
                .param("to_id", maxComplexId)
                .param("seed", command.selectionSeed())
                .param("sample_size", command.sampleSize())
                .update();
        selection
                .strata()
                .forEach(stat -> jdbc.sql("""
                    INSERT INTO building_register_profile_sample_stratum
                      (collection_id,stratum,population_count,sample_count,selection_seed,sampling_weight)
                    VALUES (:collection,:stratum,:population,:sample,:seed,:weight)
                    """)
                        .param("collection", command.collectionId())
                        .param("stratum", stat.stratum().name())
                        .param("population", stat.populationCount())
                        .param("sample", stat.sampleCount())
                        .param("seed", command.selectionSeed())
                        .param("weight", stat.samplingWeight())
                        .update());
        selection
                .entries()
                .forEach(entry -> jdbc.sql("""
                    INSERT INTO building_register_profile_sample_pnu
                      (collection_id,pnu,stratum,seed_rank,sampling_weight,complex_count)
                    VALUES (:collection,:pnu,:stratum,:rank,:weight,:complex_count)
                    """)
                        .param("collection", command.collectionId())
                        .param("pnu", entry.pnu())
                        .param("stratum", entry.stratum().name())
                        .param("rank", entry.seedRank())
                        .param("weight", entry.samplingWeight())
                        .param("complex_count", entry.complexCount())
                        .update());
        jdbc.sql("""
                    WITH frozen AS (
                        SELECT c.id,c.bc_rat,c.vl_rat,c.bld_mgm_bld_rgst_pk,p.pnu,
                               row_number() OVER (ORDER BY sample.stratum,sample.seed_rank,c.id) AS ordinal
                        FROM building_register_profile_sample_pnu sample
                        JOIN parcel p ON p.pnu=sample.pnu
                        JOIN complex c ON c.parcel_id=p.id
                        WHERE sample.collection_id=:collection
                    )
                    INSERT INTO building_register_collection_target
                      (collection_id,target_ordinal,complex_id,pnu,initial_bc_rat,initial_vl_rat,initial_bld_mgm_bld_rgst_pk)
                    SELECT :collection,ordinal,id,pnu,bc_rat,vl_rat,bld_mgm_bld_rgst_pk FROM frozen
                    """).param("collection", command.collectionId()).update();
    }

    private List<BuildingProfileSampleCandidate> candidates() {
        return jdbc.sql("""
                    WITH title_counts AS (
                        SELECT s.pnu,count(DISTINCT r.mgm_bldrgst_pk)::integer AS title_count
                        FROM building_register_record_snapshot r
                        JOIN building_register_raw_page p ON p.id=r.raw_page_id
                        JOIN building_register_endpoint_snapshot s ON s.id=p.endpoint_snapshot_id
                        WHERE r.endpoint='TITLE' AND r.regstr_kind_cd IN ('2','3')
                        GROUP BY s.pnu
                    ), risks AS (
                        SELECT pnu,true AS risky
                        FROM building_register_complex_match
                        WHERE status IN ('INCOMPLETE_HIERARCHY','SOURCE_CONFLICT','AMBIGUOUS_GENERATION')
                        GROUP BY pnu
                    ), transitions AS (
                        SELECT old_legal_dong_code FROM legal_dong_code_mapping GROUP BY old_legal_dong_code
                    )
                    SELECT p.pnu,substring(p.pnu from 1 for 2) AS region_code,
                           count(c.id)::integer AS complex_count,
                           COALESCE(max(tc.title_count),0)::integer AS title_count,
                           CASE
                             WHEN bool_or(t.old_legal_dong_code LIKE '28%') THEN 'INCHEON'
                             WHEN bool_or(t.old_legal_dong_code LIKE '29%' OR t.old_legal_dong_code LIKE '46%')
                               THEN 'GWANGJU_JEONNAM'
                             ELSE NULL
                           END AS legal_transition_group,
                           bool_or(COALESCE(r.risky,false)) AS hierarchy_risk,
                           bool_and(c.dong_cnt IS NOT NULL AND c.unit_cnt IS NOT NULL
                                    AND c.use_date IS NOT NULL AND c.bld_mgm_bld_rgst_pk IS NOT NULL) AS metadata_control
                    FROM parcel p
                    JOIN complex c ON c.parcel_id=p.id
                    LEFT JOIN title_counts tc ON tc.pnu=p.pnu
                    LEFT JOIN risks r ON r.pnu=p.pnu
                    LEFT JOIN transitions t ON t.old_legal_dong_code=substring(p.pnu from 1 for 10)
                    WHERE p.pnu ~ '^[0-9]{19}$'
                    GROUP BY p.pnu
                    ORDER BY p.pnu
                    """)
                .query((rs, rowNum) -> new BuildingProfileSampleCandidate(
                        rs.getString("pnu"),
                        rs.getString("region_code"),
                        rs.getInt("complex_count"),
                        rs.getInt("title_count"),
                        rs.getString("legal_transition_group"),
                        rs.getBoolean("hierarchy_risk"),
                        rs.getBoolean("metadata_control")))
                .list();
    }

    @Override
    public Set<String> completedPnus(UUID collectionId) {
        return Set.copyOf(jdbc.sql("""
                    SELECT pnu FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection AND collection_status='COLLECTED'
                    """)
                .param("collection", collectionId)
                .query(String.class)
                .list());
    }

    @Override
    public Optional<BuildingProfileCodeTransition> codeTransition(String originalPnu) {
        if (originalPnu == null || !originalPnu.matches("[0-9]{19}")) {
            throw new IllegalArgumentException("originalPnu must be 19 digits");
        }
        return jdbc.sql("""
                    SELECT mapping.import_id,
                           mapping.new_legal_dong_code || substring(:pnu from 11 for 9) AS candidate_pnu
                    FROM legal_dong_code_mapping mapping
                    JOIN legal_dong_code_import imported ON imported.import_id=mapping.import_id
                    WHERE mapping.old_legal_dong_code=substring(:pnu from 1 for 10)
                      AND imported.status='COMPLETED'
                    ORDER BY imported.effective_date DESC,imported.completed_at DESC
                    LIMIT 1
                    """)
                .param("pnu", originalPnu)
                .query((rs, rowNum) -> new BuildingProfileCodeTransition(
                        rs.getObject("import_id", UUID.class), rs.getString("candidate_pnu")))
                .optional();
    }

    @Override
    public void recordCodeLookup(UUID collectionId, BuildingProfileCodeLookupEvidence evidence) {
        jdbc.sql("""
                    INSERT INTO building_register_profile_code_lookup
                      (collection_id,import_id,request_id,original_pnu,candidate_pnu,old_result,new_result,
                       comparison_status,old_management_key_hashes,new_management_key_hashes)
                    VALUES (:collection,:import_id,:request_id,:original,:candidate,:old_result,:new_result,
                            :comparison,CAST(:old_hashes AS jsonb),CAST(:new_hashes AS jsonb))
                    ON CONFLICT (collection_id,import_id,original_pnu,request_id) DO NOTHING
                    """)
                .param("collection", collectionId)
                .param("import_id", evidence.importId())
                .param("request_id", evidence.requestId())
                .param("original", evidence.originalPnu())
                .param("candidate", evidence.candidatePnu())
                .param("old_result", evidence.oldResult().name())
                .param("new_result", evidence.newResult().name())
                .param("comparison", evidence.comparisonStatus().name())
                .param("old_hashes", hashes(evidence.oldManagementKeys()))
                .param("new_hashes", hashes(evidence.newManagementKeys()))
                .update();
    }

    @Override
    public void recordCollected(UUID collectionId, String pnu, Set<BuildingProfileHierarchyReason> reasons) {
        transaction.executeWithoutResult(ignored -> {
            for (BuildingProfileHierarchyReason reason : reasons) {
                jdbc.sql("""
                            INSERT INTO building_register_profile_hierarchy_reason(collection_id,pnu,reason)
                            VALUES (:collection,:pnu,:reason)
                            ON CONFLICT DO NOTHING
                            """)
                        .param("collection", collectionId)
                        .param("pnu", pnu)
                        .param("reason", reason.name())
                        .update();
            }
            jdbc.sql("""
                        UPDATE building_register_profile_sample_pnu
                        SET collection_status='COLLECTED',failure_status=NULL,completed_at=COALESCE(completed_at,now())
                        WHERE collection_id=:collection AND pnu=:pnu
                        """).param("collection", collectionId).param("pnu", pnu).update();
        });
    }

    @Override
    public void recordFailure(UUID collectionId, String pnu, String failureStatus) {
        String safe = failureStatus == null ? "UNKNOWN" : failureStatus.replaceAll("[^A-Z0-9_]", "_");
        safe = safe.substring(0, Math.min(32, safe.length()));
        jdbc.sql("""
                    UPDATE building_register_profile_sample_pnu
                    SET collection_status='FAILED',failure_status=:failure,completed_at=NULL
                    WHERE collection_id=:collection AND pnu=:pnu AND collection_status<>'COLLECTED'
                    """)
                .param("failure", safe)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .update();
    }

    @Override
    public boolean completeIfAllPnusCollected(UUID collectionId) {
        return transaction.execute(ignored -> {
            int remaining = jdbc.sql("""
                        SELECT count(*) FROM building_register_profile_sample_pnu
                        WHERE collection_id=:collection AND collection_status<>'COLLECTED'
                        """)
                    .param("collection", collectionId)
                    .query(Integer.class)
                    .single();
            if (remaining != 0) return false;
            jdbc.sql("""
                        UPDATE building_register_collection_campaign
                        SET status='COMPLETED',completed_at=COALESCE(completed_at,now())
                        WHERE collection_id=:collection AND mode='profile' AND status IN ('COLLECTING','COMPLETED')
                        """).param("collection", collectionId).update();
            return true;
        });
    }

    private record Campaign(String mode, String strategy, String selectionSeed, int sampleSize) {}

    private String hashes(Set<String> values) {
        return values.stream()
                .map(this::sha256)
                .sorted()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte current : digest) hex.append(String.format("%02x", current));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
