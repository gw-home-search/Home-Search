package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataLookupEvidence;
import com.home.application.ingest.metadata.ComplexMetadataResolution;
import com.home.application.ingest.metadata.OdcMetadataGapFillOutcome;
import com.home.application.ingest.metadata.OdcMetadataGapFillRepository;
import com.home.application.ingest.metadata.OdcMetadataGapFillTarget;
import com.home.domain.complex.metadata.ComplexMetadataRetryPolicy;
import com.home.domain.complex.metadata.ComplexMetadataStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcOdcMetadataGapFillRepository implements OdcMetadataGapFillRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ComplexMetadataRetryPolicy retryPolicy = new ComplexMetadataRetryPolicy();

    public JdbcOdcMetadataGapFillRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
    }

    @Override
    public List<OdcMetadataGapFillTarget> findTargets(int limit, Long fromId, long toId, UUID requestId) {
        return jdbc.sql("""
			SELECT c.id,c.apt_seq,c.name,p.pnu,p.address,c.metadata_attempts,
			       (SELECT count(*) FROM complex pc JOIN parcel pp ON pp.id=pc.parcel_id WHERE pp.pnu=p.pnu) pnu_complex_count
			FROM complex c JOIN parcel p ON p.id=c.parcel_id
			WHERE (c.dong_cnt IS NULL OR c.unit_cnt IS NULL OR c.use_date IS NULL)
			  AND c.metadata_hold_at IS NULL
			  AND c.id>=COALESCE(:from_id,c.id) AND c.id<=:to_id
			  AND NOT EXISTS (SELECT 1 FROM complex_metadata_enrichment_attempt a
			                  WHERE a.complex_id=c.id AND a.source='ODC')
			ORDER BY c.id LIMIT :limit
			""")
                .param("from_id", fromId)
                .param("to_id", toId)
                .param("limit", limit)
                .query(this::target)
                .list();
    }

    @Override
    public OdcMetadataGapFillOutcome recordAmbiguous(OdcMetadataGapFillTarget target, UUID requestId) {
        return transaction.execute(status -> {
            LockedComplex locked = lock(target.lookup().complexId());
            OdcMetadataGapFillOutcome duplicate = existingOutcome(locked.id(), requestId);
            if (duplicate != null) return duplicate;
            return record(
                    locked,
                    ComplexMetadataResolution.ambiguous("ODC", "multiple complexes share the requested PNU"),
                    false,
                    requestId);
        });
    }

    @Override
    public OdcMetadataGapFillOutcome saveResolution(
            OdcMetadataGapFillTarget target, ComplexMetadataResolution resolution, UUID requestId) {
        return transaction.execute(status -> saveLocked(target, resolution, requestId));
    }

    private OdcMetadataGapFillOutcome saveLocked(
            OdcMetadataGapFillTarget target, ComplexMetadataResolution resolution, UUID requestId) {
        LockedComplex locked = lock(target.lookup().complexId());
        OdcMetadataGapFillOutcome duplicate = existingOutcome(locked.id(), requestId);
        if (duplicate != null) return duplicate;
        if (!Objects.equals(locked.pnu(), target.lookup().pnu()) || pnuCount(locked.pnu()) != 1) {
            return record(
                    locked,
                    ComplexMetadataResolution.ambiguous("ODC", "PNU changed or is no longer unique"),
                    false,
                    requestId);
        }
        ComplexMetadata candidate = resolution.metadata();
        if (candidate != null && conflicts(locked, candidate)) {
            return record(
                    locked,
                    ComplexMetadataResolution.ambiguous("ODC", "existing core metadata conflicts with ODC candidate"),
                    false,
                    requestId);
        }
        if (candidate == null) return record(locked, resolution, false, requestId);

        Integer dong = locked.dongCnt() != null ? locked.dongCnt() : candidate.dongCnt();
        Integer unit = locked.unitCnt() != null ? locked.unitCnt() : candidate.unitCnt();
        LocalDate useDate = locked.useDate() != null ? locked.useDate() : candidate.useDate();
        boolean applied = !Objects.equals(locked.dongCnt(), dong)
                || !Objects.equals(locked.unitCnt(), unit)
                || !Objects.equals(locked.useDate(), useDate);
        ComplexMetadataStatus status = dong != null && dong > 0 && unit != null && unit > 0 && useDate != null
                ? ComplexMetadataStatus.RESOLVED
                : ComplexMetadataStatus.PARTIAL;
        ComplexMetadataResolution projected = new ComplexMetadataResolution(
                status,
                new ComplexMetadata(dong, unit, null, null, null, null, null, useDate),
                "ODC",
                null,
                null,
                resolution.lookupEvidence());
        jdbc.sql("""
			UPDATE complex SET dong_cnt=:dong,unit_cnt=:unit,use_date=:use_date,metadata_status=:status,
			 metadata_source=CASE WHEN :applied THEN 'ODC' ELSE metadata_source END,
			 metadata_failure_kind=NULL,metadata_failure_reason=NULL,metadata_next_attempt_at=NULL,
			 metadata_checked_at=now(),updated_at=CASE WHEN :applied THEN now() ELSE updated_at END
			WHERE id=:id
			""")
                .param("dong", dong)
                .param("unit", unit)
                .param("use_date", useDate)
                .param("status", status.name())
                .param("applied", applied)
                .param("id", locked.id())
                .update();
        return record(locked, projected, applied, requestId);
    }

    private OdcMetadataGapFillOutcome record(
            LockedComplex locked, ComplexMetadataResolution resolution, boolean applied, UUID requestId) {
        Instant next = retryPolicy
                .nextAttemptAt(resolution.status(), resolution.failureKind(), locked.attempts() + 1, Instant.now())
                .orElse(null);
        int attemptNo = jdbc.sql("""
			UPDATE complex SET metadata_attempts=metadata_attempts+1,metadata_checked_at=now(),
			 metadata_status=:status,metadata_failure_kind=:kind,metadata_failure_reason=:reason,
			 metadata_next_attempt_at=:next_at WHERE id=:id RETURNING metadata_attempts
			""")
                .param("status", resolution.status().name())
                .param(
                        "kind",
                        resolution.failureKind() == null
                                ? null
                                : resolution.failureKind().name())
                .param("reason", resolution.failureReason())
                .param("next_at", offset(next))
                .param("id", locked.id())
                .query(Integer.class)
                .single();
        ComplexMetadataLookupEvidence evidence = resolution.lookupEvidence();
        jdbc.sql("""
			INSERT INTO complex_metadata_enrichment_attempt
			(complex_id,attempt_no,status,source,failure_kind,failure_reason,next_attempt_at,lookup_path,
			 requested_pnu,resolved_source_pnu,alias_id,candidate_count,request_id,projection_applied)
			VALUES (:id,:attempt,:status,'ODC',:kind,:reason,:next_at,:lookup_path,:requested_pnu,
			 :resolved_pnu,:alias_id,:candidate_count,:request_id,:applied)
			""")
                .param("id", locked.id())
                .param("attempt", attemptNo)
                .param("status", resolution.status().name())
                .param(
                        "kind",
                        resolution.failureKind() == null
                                ? null
                                : resolution.failureKind().name())
                .param("reason", resolution.failureReason())
                .param("next_at", offset(next))
                .param("lookup_path", evidence.lookupPath().name())
                .param("requested_pnu", locked.pnu())
                .param("resolved_pnu", evidence.resolvedSourcePnu())
                .param("alias_id", evidence.aliasId())
                .param("candidate_count", evidence.candidateCount())
                .param("request_id", requestId)
                .param("applied", applied)
                .update();
        return new OdcMetadataGapFillOutcome(resolution.status(), applied);
    }

    private boolean conflicts(LockedComplex locked, ComplexMetadata candidate) {
        return differs(locked.dongCnt(), candidate.dongCnt())
                || differs(locked.unitCnt(), candidate.unitCnt())
                || differs(locked.useDate(), candidate.useDate());
    }

    private boolean differs(Object current, Object candidate) {
        return current != null && candidate != null && !current.equals(candidate);
    }

    private OdcMetadataGapFillOutcome existingOutcome(long complexId, UUID requestId) {
        return jdbc.sql("""
			SELECT status,projection_applied FROM complex_metadata_enrichment_attempt
			WHERE complex_id=:id AND source='ODC' AND request_id=:request_id ORDER BY id DESC LIMIT 1
			""")
                .param("id", complexId)
                .param("request_id", requestId)
                .query((rs, row) -> new OdcMetadataGapFillOutcome(
                        ComplexMetadataStatus.valueOf(rs.getString("status")), rs.getBoolean("projection_applied")))
                .optional()
                .orElse(null);
    }

    private LockedComplex lock(long id) {
        return jdbc.sql("""
			SELECT c.id,p.pnu,c.metadata_attempts,c.dong_cnt,c.unit_cnt,c.use_date
			FROM complex c JOIN parcel p ON p.id=c.parcel_id WHERE c.id=:id FOR UPDATE OF c
			""")
                .param("id", id)
                .query((rs, row) -> new LockedComplex(
                        rs.getLong("id"),
                        rs.getString("pnu"),
                        rs.getInt("metadata_attempts"),
                        (Integer) rs.getObject("dong_cnt"),
                        (Integer) rs.getObject("unit_cnt"),
                        rs.getObject("use_date", LocalDate.class)))
                .single();
    }

    private int pnuCount(String pnu) {
        return jdbc.sql("SELECT count(*) FROM complex c JOIN parcel p ON p.id=c.parcel_id WHERE p.pnu=:pnu")
                .param("pnu", pnu)
                .query(Integer.class)
                .single();
    }

    private OdcMetadataGapFillTarget target(ResultSet rs, int row) throws SQLException {
        return new OdcMetadataGapFillTarget(
                new ComplexMetadataLookup(
                        rs.getLong("id"),
                        rs.getString("apt_seq"),
                        rs.getString("name"),
                        rs.getString("pnu"),
                        rs.getString("address"),
                        rs.getInt("metadata_attempts")),
                rs.getInt("pnu_complex_count"));
    }

    private OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private record LockedComplex(
            long id, String pnu, int attempts, Integer dongCnt, Integer unitCnt, LocalDate useDate) {}
}
