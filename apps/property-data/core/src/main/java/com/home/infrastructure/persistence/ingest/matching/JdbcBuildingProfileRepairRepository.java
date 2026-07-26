package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingprofile.BuildingProfileCollectTarget;
import com.home.application.ingest.buildingprofile.BuildingProfileRepairCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileRepairRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingProfileRepairRepository implements BuildingProfileRepairRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingProfileRepairRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public List<BuildingProfileCollectTarget> freezeOrLoad(BuildingProfileRepairCommand command) {
        transaction.executeWithoutResult(ignored -> freeze(command));
        return jdbc.sql("""
                    SELECT pnu,complex_count
                    FROM building_register_profile_sample_pnu
                    WHERE collection_id=:collection
                    ORDER BY seed_rank,pnu
                    """)
                .param("collection", command.collectionId())
                .query((rs, rowNum) ->
                        new BuildingProfileCollectTarget(rs.getString("pnu"), rs.getInt("complex_count")))
                .list();
    }

    private void freeze(BuildingProfileRepairCommand command) {
        RepairRun existing = load(command.collectionId());
        if (existing != null) {
            if (!existing.sourceCollectionId().equals(command.sourceCollectionId())
                    || !existing.requestId().equals(command.requestId())
                    || !existing.runDate().equals(command.runDate())
                    || !existing.policyVersion().equals(command.repairPolicyVersion())
                    || existing.maxRequests() != command.maxRequests()
                    || existing.parallelism() != command.parallelism()) {
                throw new IllegalArgumentException("collectionId is already frozen with different repair inputs");
            }
            if ("FAILED".equals(existing.status())) {
                throw new IllegalStateException("failed profile repair cannot resume");
            }
            return;
        }
        boolean sourceExists = jdbc.sql("""
                    SELECT EXISTS (
                      SELECT 1 FROM building_register_collection_campaign
                      WHERE collection_id=:source AND mode='profile' AND status='COMPLETED')
                    """)
                .param("source", command.sourceCollectionId())
                .query(Boolean.class)
                .single();
        if (!sourceExists) throw new IllegalStateException("completed source profile collection is required");

        List<Target> targets = jdbc.sql("""
                    WITH latest_endpoint AS (
                      SELECT DISTINCT ON (pnu,endpoint) pnu,endpoint,status
                      FROM building_register_endpoint_snapshot
                      WHERE collection_id=:source
                      ORDER BY pnu,endpoint,run_date DESC,attempt_no DESC,id DESC
                    ), repair_pnu AS (
                      SELECT pnu FROM latest_endpoint
                      WHERE status IN ('PROVIDER_FAILED','PARSE_FAILED','PERMANENT_OVERSIZED')
                      UNION
                      SELECT pnu FROM building_register_profile_hierarchy_reason
                      WHERE collection_id=:source
                    )
                    SELECT repair_pnu.pnu,count(complex_row.id)::integer AS complex_count
                    FROM repair_pnu
                    JOIN parcel ON parcel.pnu=repair_pnu.pnu
                    JOIN complex complex_row ON complex_row.parcel_id=parcel.id
                    GROUP BY repair_pnu.pnu
                    ORDER BY repair_pnu.pnu
                    """)
                .param("source", command.sourceCollectionId())
                .query((rs, rowNum) -> new Target(rs.getString("pnu"), rs.getInt("complex_count")))
                .list();
        if (targets.isEmpty()) throw new IllegalStateException("source profile collection has no repair targets");

        long maxComplexId =
                jdbc.sql("SELECT max(id) FROM complex").query(Long.class).single();
        String seed = "repair:" + command.sourceCollectionId() + ":" + command.repairPolicyVersion();
        jdbc.sql("""
                    INSERT INTO building_register_collection_campaign(
                      collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES (:collection,'profile','COMPARE_RECAP_TITLE',:max_id,'COLLECTING',
                      'PROFILE_DISCOVERY','NATIONWIDE_STAGING',:seed,:target_count)
                    """)
                .param("collection", command.collectionId())
                .param("max_id", maxComplexId)
                .param("seed", seed)
                .param("target_count", targets.size())
                .update();
        jdbc.sql("""
                    INSERT INTO building_register_profile_sample_stratum(
                      collection_id,stratum,population_count,sample_count,selection_seed,sampling_weight)
                    VALUES (:collection,'NATIONWIDE_CENSUS',:count,:count,:seed,1)
                    """)
                .param("collection", command.collectionId())
                .param("count", targets.size())
                .param("seed", seed)
                .update();
        for (int index = 0; index < targets.size(); index++) {
            Target target = targets.get(index);
            jdbc.sql("""
                        INSERT INTO building_register_profile_sample_pnu(
                          collection_id,pnu,stratum,seed_rank,sampling_weight,complex_count)
                        VALUES (:collection,:pnu,'NATIONWIDE_CENSUS',:rank,1,:complex_count)
                        """)
                    .param("collection", command.collectionId())
                    .param("pnu", target.pnu())
                    .param("rank", index)
                    .param("complex_count", target.complexCount())
                    .update();
        }
        jdbc.sql("""
                    WITH frozen AS (
                      SELECT complex_row.id,complex_row.bc_rat,complex_row.vl_rat,
                             complex_row.bld_mgm_bld_rgst_pk,parcel.pnu,
                             row_number() OVER (ORDER BY sample.seed_rank,complex_row.id) AS ordinal
                      FROM building_register_profile_sample_pnu sample
                      JOIN parcel ON parcel.pnu=sample.pnu
                      JOIN complex complex_row ON complex_row.parcel_id=parcel.id
                      WHERE sample.collection_id=:collection)
                    INSERT INTO building_register_collection_target(
                      collection_id,target_ordinal,complex_id,pnu,initial_bc_rat,initial_vl_rat,
                      initial_bld_mgm_bld_rgst_pk)
                    SELECT :collection,ordinal,id,pnu,bc_rat,vl_rat,bld_mgm_bld_rgst_pk FROM frozen
                    """).param("collection", command.collectionId()).update();
        jdbc.sql("""
                    INSERT INTO building_register_profile_repair_run(
                      collection_id,source_collection_id,request_id,run_date,repair_policy_version,
                      max_requests,parallelism,status,target_count)
                    VALUES (:collection,:source,:request,:run_date,:policy,:max_requests,:parallelism,'RUNNING',:count)
                    """)
                .param("collection", command.collectionId())
                .param("source", command.sourceCollectionId())
                .param("request", command.requestId())
                .param("run_date", command.runDate())
                .param("policy", command.repairPolicyVersion())
                .param("max_requests", command.maxRequests())
                .param("parallelism", command.parallelism())
                .param("count", targets.size())
                .update();
    }

    @Override
    public int transientFailureCount(UUID collectionId, String pnu) {
        return jdbc.sql("""
                    SELECT COALESCE(max(failure_count),0)::integer
                    FROM (
                      SELECT endpoint,count(*)::integer AS failure_count
                      FROM building_register_endpoint_snapshot
                      WHERE collection_id=:collection AND pnu=:pnu AND status='PROVIDER_FAILED'
                      GROUP BY endpoint) failures
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .query(Integer.class)
                .single();
    }

    @Override
    public void recordProgress(
            UUID collectionId, int requestCount, int completedCount, int failureCount, boolean completed) {
        jdbc.sql("""
                    UPDATE building_register_profile_repair_run repair
                    SET request_count=repair.request_count+:requests,
                        completed_count=(SELECT count(*) FROM building_register_profile_sample_pnu
                                         WHERE collection_id=:collection AND collection_status='COLLECTED'),
                        failure_count=(SELECT count(*) FROM building_register_profile_sample_pnu
                                       WHERE collection_id=:collection AND collection_status='FAILED'),
                        status=CASE WHEN :completed THEN 'COMPLETED' ELSE repair.status END,
                        completed_at=CASE WHEN :completed THEN COALESCE(repair.completed_at,now()) ELSE NULL END
                    WHERE collection_id=:collection AND status IN ('RUNNING','COMPLETED')
                    """)
                .param("requests", requestCount)
                .param("collection", collectionId)
                .param("completed", completed)
                .update();
    }

    private RepairRun load(UUID collectionId) {
        return jdbc.sql("""
                    SELECT source_collection_id,request_id,run_date,repair_policy_version,
                           max_requests,parallelism,status
                    FROM building_register_profile_repair_run
                    WHERE collection_id=:collection FOR UPDATE
                    """)
                .param("collection", collectionId)
                .query((rs, rowNum) -> new RepairRun(
                        rs.getObject("source_collection_id", UUID.class),
                        rs.getObject("request_id", UUID.class),
                        rs.getObject("run_date", LocalDate.class),
                        rs.getString("repair_policy_version"),
                        rs.getInt("max_requests"),
                        rs.getInt("parallelism"),
                        rs.getString("status")))
                .optional()
                .orElse(null);
    }

    private record Target(String pnu, int complexCount) {}

    private record RepairRun(
            UUID sourceCollectionId,
            UUID requestId,
            LocalDate runDate,
            String policyVersion,
            int maxRequests,
            int parallelism,
            String status) {}
}
