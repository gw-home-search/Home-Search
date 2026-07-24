package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRegisterRawPageReceiptCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterRawPageRepository;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcBuildingRegisterRawPageRepository implements BuildingRegisterRawPageRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcBuildingRegisterRawPageRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public long receive(BuildingRegisterRawPageReceiptCommand command) {
        Long inserted = jdbc.sql("""
                    INSERT INTO building_register_raw_page
                        (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,
                         body_sha256,byte_count,http_status,provider_status)
                    VALUES (:snapshot_id,:request_id,:page_no,:attempt_no,'RECEIVED',:body,
                            :hash,:byte_count,:http_status,:provider_status)
                    ON CONFLICT (endpoint_snapshot_id,page_no,attempt_no) DO NOTHING
                    RETURNING id
                    """)
                .param("snapshot_id", command.endpointSnapshotId())
                .param("request_id", command.requestId())
                .param("page_no", command.pageNo())
                .param("attempt_no", command.attemptNo())
                .param("body", command.responseBody())
                .param("hash", command.bodySha256())
                .param("byte_count", command.byteCount())
                .param("http_status", command.httpStatus())
                .param("provider_status", command.providerStatus())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (inserted != null) return inserted;
        return jdbc.sql("""
                    SELECT id FROM building_register_raw_page
                    WHERE endpoint_snapshot_id=:snapshot_id AND page_no=:page_no AND attempt_no=:attempt_no
                    """)
                .param("snapshot_id", command.endpointSnapshotId())
                .param("page_no", command.pageNo())
                .param("attempt_no", command.attemptNo())
                .query(Long.class)
                .single();
    }

    @Override
    public void complete(
            long rawPageId, BuildingRegisterRawPageStatus next, List<BuildingRegisterRecordSnapshotCommand> records) {
        complete(rawPageId, next, null, records);
    }

    @Override
    public void complete(
            long rawPageId,
            BuildingRegisterRawPageStatus next,
            String providerStatus,
            List<BuildingRegisterRecordSnapshotCommand> records) {
        Objects.requireNonNull(next, "status");
        Objects.requireNonNull(records, "records");
        String normalizedProviderStatus = providerStatus == null ? null : providerStatus.trim();
        if (normalizedProviderStatus != null
                && (normalizedProviderStatus.isEmpty() || normalizedProviderStatus.length() > 32)) {
            throw new IllegalArgumentException("providerStatus must be 1..32 characters");
        }
        transaction.executeWithoutResult(
                ignored -> completeInTransaction(rawPageId, next, normalizedProviderStatus, records));
    }

    private void completeInTransaction(
            long rawPageId,
            BuildingRegisterRawPageStatus next,
            String providerStatus,
            List<BuildingRegisterRecordSnapshotCommand> records) {
        BuildingRegisterRawPageStatus current = jdbc.sql(
                        "SELECT status FROM building_register_raw_page WHERE id=:id FOR UPDATE")
                .param("id", rawPageId)
                .query(String.class)
                .optional()
                .map(BuildingRegisterRawPageStatus::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("raw page not found: " + rawPageId));
        if (providerStatus != null) {
            int updated = jdbc.sql("""
                        UPDATE building_register_raw_page SET provider_status=:provider
                        WHERE id=:id AND (provider_status IS NULL OR provider_status=:provider)
                        """)
                    .param("provider", providerStatus)
                    .param("id", rawPageId)
                    .update();
            if (updated != 1) throw new IllegalStateException("raw page provider status conflicts with stored state");
        }
        if (current == next && current.isFinalized()) return;
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("invalid raw page transition: " + current + " -> " + next);
        }
        validateRecords(next, records);
        if (next == BuildingRegisterRawPageStatus.PARSED) {
            records.forEach(record -> insertRecord(rawPageId, record));
        }
        jdbc.sql("""
                    UPDATE building_register_raw_page
                    SET status=:status, finalized_at=now()
                    WHERE id=:id
                    """).param("status", next.name()).param("id", rawPageId).update();
    }

    private void validateRecords(
            BuildingRegisterRawPageStatus status, List<BuildingRegisterRecordSnapshotCommand> records) {
        if (status == BuildingRegisterRawPageStatus.PARSED && records.isEmpty()) {
            throw new IllegalArgumentException("PARSED raw page requires records");
        }
        if (status != BuildingRegisterRawPageStatus.PARSED && !records.isEmpty()) {
            throw new IllegalArgumentException(status + " raw page must not contain records");
        }
    }

    private void insertRecord(long rawPageId, BuildingRegisterRecordSnapshotCommand record) {
        jdbc.sql("""
                    INSERT INTO building_register_record_snapshot
                        (raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,mgm_up_bldrgst_pk,
                         regstr_gb_cd,regstr_kind_cd,new_old_regstr_gb_cd,main_atch_gb_cd,bld_nm,dong_nm,
                         main_purps_cd,plat_area,arch_area,tot_area,vl_rat_estm_tot_area,bc_rat,vl_rat,
                         main_bld_cnt,atch_bld_cnt,hhld_cnt,use_apr_day,crtn_day)
                    VALUES
                        (:raw_page_id,:item_index,:pnu,:endpoint,:key,:parent_key,
                         :register_group,:register_kind,:new_old,:main_attached,:building_name,:dong_name,
                         :main_purpose,:plat_area,:arch_area,:total_area,:ratio_estimate_area,:bc_ratio,:vl_ratio,
                         :main_count,:attached_count,:household_count,:use_date,:creation_date)
                    """)
                .param("raw_page_id", rawPageId)
                .param("item_index", record.itemIndex())
                .param("pnu", record.pnu())
                .param("endpoint", record.endpoint().name())
                .param("key", record.managementKey())
                .param("parent_key", record.parentManagementKey())
                .param("register_group", record.registerGroupCode())
                .param("register_kind", record.registerKindCode())
                .param("new_old", record.newOldRegisterCode())
                .param("main_attached", record.mainAttachedCode())
                .param("building_name", record.buildingName())
                .param("dong_name", record.dongName())
                .param("main_purpose", record.mainPurposeCode())
                .param("plat_area", record.platArea())
                .param("arch_area", record.archArea())
                .param("total_area", record.totalArea())
                .param("ratio_estimate_area", record.floorRatioEstimateTotalArea())
                .param("bc_ratio", record.buildingCoverageRatio())
                .param("vl_ratio", record.floorAreaRatio())
                .param("main_count", record.mainBuildingCount())
                .param("attached_count", record.attachedBuildingCount())
                .param("household_count", record.householdCount())
                .param("use_date", record.useApprovalDate())
                .param("creation_date", record.creationDate())
                .update();
    }

    @Override
    public String body(long rawPageId) {
        return jdbc.sql("SELECT response_body FROM building_register_raw_page WHERE id=:id")
                .param("id", rawPageId)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
