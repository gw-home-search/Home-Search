package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.application.ingest.buildingregister.BuildingRegisterCompletedPage;
import com.home.application.ingest.buildingregister.BuildingRegisterEndpointSnapshot;
import com.home.application.ingest.buildingregister.BuildingRegisterEndpointSnapshotStore;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingRegisterEndpointSnapshotStore implements BuildingRegisterEndpointSnapshotStore {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingRegisterEndpointSnapshotStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public BuildingRegisterEndpointSnapshot open(
            UUID collectionId, String pnu, BuildingRegisterEndpoint endpoint, LocalDate runDate, int pageSize) {
        return transaction.execute(status -> openInTransaction(collectionId, pnu, endpoint, runDate, pageSize));
    }

    private BuildingRegisterEndpointSnapshot openInTransaction(
            UUID collectionId, String pnu, BuildingRegisterEndpoint endpoint, LocalDate runDate, int pageSize) {
        Optional<BuildingRegisterEndpointSnapshot> existing = jdbc.sql("""
                    SELECT id,endpoint,page_size,attempt_no
                    FROM building_register_endpoint_snapshot
                    WHERE collection_id=:collection AND pnu=:pnu AND endpoint=:endpoint
                      AND run_date=:run_date AND page_size=:page_size
                      AND status IN ('ACTIVE','PARSED','EMPTY')
                    ORDER BY attempt_no DESC LIMIT 1
                    FOR UPDATE
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("run_date", runDate)
                .param("page_size", pageSize)
                .query(this::snapshot)
                .optional();
        if (existing.isPresent()) return existing.get();
        Optional<BuildingRegisterEndpointSnapshot> resumable = jdbc.sql("""
                    SELECT id,endpoint,page_size,attempt_no
                    FROM building_register_endpoint_snapshot
                    WHERE collection_id=:collection AND pnu=:pnu AND endpoint=:endpoint
                      AND page_size=:page_size AND status IN ('ACTIVE','PARSED','EMPTY')
                    ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                             run_date DESC,attempt_no DESC,id DESC LIMIT 1
                    FOR UPDATE
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("page_size", pageSize)
                .query(this::snapshot)
                .optional();
        if (resumable.isPresent()) return resumable.get();
        BuildingRegisterEndpointSnapshot cloned =
                cloneCompletedRepairSnapshot(collectionId, pnu, endpoint, runDate, pageSize);
        if (cloned != null) return cloned;
        int attempt = jdbc.sql("""
                    SELECT COALESCE(max(attempt_no),0)+1
                    FROM building_register_endpoint_snapshot
                    WHERE collection_id=:collection AND pnu=:pnu AND endpoint=:endpoint AND run_date=:run_date
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("run_date", runDate)
                .query(Integer.class)
                .single();
        return jdbc.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                        (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status)
                    VALUES (:collection,:pnu,:endpoint,:run_date,:page_size,:attempt,'ACTIVE')
                    RETURNING id,endpoint,page_size,attempt_no
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("run_date", runDate)
                .param("page_size", pageSize)
                .param("attempt", attempt)
                .query(this::snapshot)
                .single();
    }

    private BuildingRegisterEndpointSnapshot cloneCompletedRepairSnapshot(
            UUID collectionId, String pnu, BuildingRegisterEndpoint endpoint, LocalDate runDate, int pageSize) {
        SourceSnapshot source = jdbc.sql("""
                    SELECT source_snapshot.id,source_snapshot.status,source_snapshot.total_count
                    FROM building_register_profile_repair_run repair
                    JOIN LATERAL (
                      SELECT id,status,total_count
                      FROM building_register_endpoint_snapshot
                      WHERE collection_id=repair.source_collection_id
                        AND pnu=:pnu AND endpoint=:endpoint AND page_size=:page_size
                      ORDER BY run_date DESC,attempt_no DESC,id DESC LIMIT 1
                    ) source_snapshot ON true
                    WHERE repair.collection_id=:collection
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("page_size", pageSize)
                .query((rs, rowNum) -> new SourceSnapshot(
                        rs.getLong("id"), rs.getString("status"), rs.getObject("total_count", Integer.class)))
                .optional()
                .orElse(null);
        if (source == null || !("PARSED".equals(source.status()) || "EMPTY".equals(source.status()))) {
            return null;
        }
        long targetSnapshotId = jdbc.sql("""
                    INSERT INTO building_register_endpoint_snapshot(
                      collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:collection,:pnu,:endpoint,:run_date,:page_size,1,:status,:total,now())
                    RETURNING id
                    """)
                .param("collection", collectionId)
                .param("pnu", pnu)
                .param("endpoint", endpoint.name())
                .param("run_date", runDate)
                .param("page_size", pageSize)
                .param("status", source.status())
                .param("total", source.totalCount())
                .query(Long.class)
                .single();
        List<Long> sourceRawPages =
                jdbc.sql("""
                    SELECT id FROM building_register_raw_page
                    WHERE endpoint_snapshot_id=:snapshot AND status IN ('PARSED','EMPTY')
                    ORDER BY page_no
                    """).param("snapshot", source.id()).query(Long.class).list();
        if (sourceRawPages.isEmpty()) {
            throw new IllegalStateException("completed source endpoint has no finalized raw page");
        }
        for (Long sourceRawPageId : sourceRawPages) {
            long targetRawPageId = jdbc.sql("""
                        INSERT INTO building_register_raw_page(
                          endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,
                          body_sha256,byte_count,http_status,provider_status,finalized_at)
                        SELECT :target,request_id,page_no,1,status,response_body,
                               body_sha256,byte_count,http_status,provider_status,now()
                        FROM building_register_raw_page WHERE id=:source
                        RETURNING id
                        """)
                    .param("target", targetSnapshotId)
                    .param("source", sourceRawPageId)
                    .query(Long.class)
                    .single();
            jdbc.sql("""
                        INSERT INTO building_register_record_snapshot(
                          raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,
                          regstr_gb_cd,regstr_kind_cd,new_old_regstr_gb_cd,main_atch_gb_cd,bld_nm,dong_nm,
                          main_purps_cd,plat_area,arch_area,tot_area,vl_rat_estm_tot_area,bc_rat,vl_rat,
                          main_bld_cnt,atch_bld_cnt,hhld_cnt,use_apr_day,crtn_day)
                        SELECT :target,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,
                               regstr_gb_cd,regstr_kind_cd,new_old_regstr_gb_cd,main_atch_gb_cd,bld_nm,dong_nm,
                               main_purps_cd,plat_area,arch_area,tot_area,vl_rat_estm_tot_area,bc_rat,vl_rat,
                               main_bld_cnt,atch_bld_cnt,hhld_cnt,use_apr_day,crtn_day
                        FROM building_register_record_snapshot WHERE raw_page_id=:source
                        ORDER BY item_index
                        """)
                    .param("target", targetRawPageId)
                    .param("source", sourceRawPageId)
                    .update();
        }
        return new BuildingRegisterEndpointSnapshot(targetSnapshotId, endpoint, pageSize, 1);
    }

    @Override
    public Optional<BuildingRegisterCompletedPage> completedPage(long snapshotId, int pageNo) {
        Optional<RawPage> raw = jdbc.sql("""
                    SELECT rp.id,s.total_count
                    FROM building_register_raw_page rp
                    JOIN building_register_endpoint_snapshot s ON s.id=rp.endpoint_snapshot_id
                    WHERE rp.endpoint_snapshot_id=:snapshot AND rp.page_no=:page
                      AND rp.status IN ('PARSED','EMPTY') AND s.total_count IS NOT NULL
                    ORDER BY rp.attempt_no DESC LIMIT 1
                    """)
                .param("snapshot", snapshotId)
                .param("page", pageNo)
                .query((resultSet, rowNum) -> new RawPage(resultSet.getLong("id"), resultSet.getInt("total_count")))
                .optional();
        if (raw.isEmpty()) return Optional.empty();
        List<BuildingRegisterRecordSnapshotCommand> records =
                jdbc.sql("""
                    SELECT item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,regstr_gb_cd,
                           regstr_kind_cd,new_old_regstr_gb_cd,main_atch_gb_cd,bld_nm,dong_nm,main_purps_cd,
                           plat_area,arch_area,tot_area,vl_rat_estm_tot_area,bc_rat,vl_rat,
                           main_bld_cnt,atch_bld_cnt,hhld_cnt,use_apr_day,crtn_day
                    FROM building_register_record_snapshot WHERE raw_page_id=:raw ORDER BY item_index
                    """).param("raw", raw.get().id()).query(this::record).list();
        return Optional.of(new BuildingRegisterCompletedPage(raw.get().totalCount(), records));
    }

    @Override
    public void observeTotalCount(long snapshotId, int totalCount) {
        int updated =
                jdbc.sql("""
                    UPDATE building_register_endpoint_snapshot
                    SET total_count=COALESCE(total_count,:total)
                    WHERE id=:id AND status='ACTIVE' AND (total_count IS NULL OR total_count=:total)
                    """).param("total", totalCount).param("id", snapshotId).update();
        if (updated != 1) throw new IllegalStateException("endpoint totalCount changed or snapshot is not active");
    }

    @Override
    public void complete(long snapshotId, int totalCount, BuildingRegisterCollectionStatus status) {
        String stored = storedStatus(status, totalCount);
        if (status != BuildingRegisterCollectionStatus.COLLECTED) {
            int failed = jdbc.sql("""
                        UPDATE building_register_endpoint_snapshot
                        SET status=:status,completed_at=now()
                        WHERE id=:id AND (status='ACTIVE' OR status=:status)
                        """)
                    .param("status", stored)
                    .param("id", snapshotId)
                    .update();
            if (failed != 1) throw new IllegalStateException("endpoint snapshot failure conflicts with stored state");
            return;
        }
        int updated = jdbc.sql("""
                    UPDATE building_register_endpoint_snapshot
                    SET status=:status,total_count=:total,completed_at=now()
                    WHERE id=:id AND (status='ACTIVE' OR status=:status)
                      AND (total_count IS NULL OR total_count=:total)
                    """)
                .param("status", stored)
                .param("total", totalCount)
                .param("id", snapshotId)
                .update();
        if (updated != 1) throw new IllegalStateException("endpoint snapshot completion conflicts with stored state");
    }

    @Override
    public void abandonOversized(long snapshotId, int pageSize, boolean permanent) {
        String status = permanent ? "PERMANENT_OVERSIZED" : "ABANDONED_OVERSIZED";
        int updated = jdbc.sql("""
                    UPDATE building_register_endpoint_snapshot
                    SET status=:status,completed_at=now()
                    WHERE id=:id AND page_size=:page_size AND status='ACTIVE'
                    """)
                .param("status", status)
                .param("id", snapshotId)
                .param("page_size", pageSize)
                .update();
        if (updated != 1) throw new IllegalStateException("endpoint snapshot cannot be abandoned");
    }

    private String storedStatus(BuildingRegisterCollectionStatus status, int totalCount) {
        return switch (status) {
            case COLLECTED -> totalCount == 0 ? "EMPTY" : "PARSED";
            case PROVIDER_FAILED -> "PROVIDER_FAILED";
            case PARSE_FAILED -> "PARSE_FAILED";
            case PERMANENT_OVERSIZED -> "PERMANENT_OVERSIZED";
        };
    }

    private BuildingRegisterEndpointSnapshot snapshot(ResultSet resultSet, int rowNum) throws SQLException {
        return new BuildingRegisterEndpointSnapshot(
                resultSet.getLong("id"),
                BuildingRegisterEndpoint.valueOf(resultSet.getString("endpoint")),
                resultSet.getInt("page_size"),
                resultSet.getInt("attempt_no"));
    }

    private BuildingRegisterRecordSnapshotCommand record(ResultSet resultSet, int rowNum) throws SQLException {
        return new BuildingRegisterRecordSnapshotCommand(
                resultSet.getInt("item_index"),
                resultSet.getString("pnu"),
                BuildingRegisterEndpoint.valueOf(resultSet.getString("endpoint")),
                resultSet.getString("mgm_bldrgst_pk"),
                resultSet.getString("mgm_up_bldrgst_pk"),
                resultSet.getString("regstr_gb_cd"),
                resultSet.getString("regstr_kind_cd"),
                resultSet.getString("new_old_regstr_gb_cd"),
                resultSet.getString("main_atch_gb_cd"),
                resultSet.getString("bld_nm"),
                resultSet.getString("dong_nm"),
                resultSet.getString("main_purps_cd"),
                resultSet.getBigDecimal("plat_area"),
                resultSet.getBigDecimal("arch_area"),
                resultSet.getBigDecimal("tot_area"),
                resultSet.getBigDecimal("vl_rat_estm_tot_area"),
                resultSet.getBigDecimal("bc_rat"),
                resultSet.getBigDecimal("vl_rat"),
                resultSet.getObject("main_bld_cnt", Integer.class),
                resultSet.getObject("atch_bld_cnt", Integer.class),
                resultSet.getObject("hhld_cnt", Integer.class),
                resultSet.getObject("use_apr_day", LocalDate.class),
                resultSet.getObject("crtn_day", LocalDate.class));
    }

    private record RawPage(long id, int totalCount) {}

    private record SourceSnapshot(long id, String status, Integer totalCount) {}
}
