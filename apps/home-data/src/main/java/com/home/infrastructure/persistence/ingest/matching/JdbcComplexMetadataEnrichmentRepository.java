package com.home.infrastructure.persistence.ingest.matching;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import com.home.application.ingest.metadata.ComplexMetadata;
import com.home.application.ingest.metadata.ComplexMetadataEnrichmentRepository;
import com.home.application.ingest.metadata.ComplexMetadataLookup;
import com.home.application.ingest.metadata.ComplexMetadataResolution;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexMetadataEnrichmentRepository implements ComplexMetadataEnrichmentRepository {

	private final JdbcClient jdbcClient;

	public JdbcComplexMetadataEnrichmentRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexMetadataLookup> findPending(int limit) {
		return jdbcClient.sql("""
			SELECT
			    c.id,
			    c.apt_seq,
			    c.name,
			    p.pnu,
			    p.address,
			    c.metadata_attempts
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE (
			    c.metadata_status = 'PENDING'
			    OR (
			        c.metadata_status IN ('FAILED', 'PARTIAL', 'UNAVAILABLE')
			        AND c.metadata_next_attempt_at IS NOT NULL
			        AND c.metadata_next_attempt_at <= now()
			    )
			)
			  AND c.metadata_hold_at IS NULL
			ORDER BY c.id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new ComplexMetadataLookup(
				resultSet.getLong("id"),
				resultSet.getString("apt_seq"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				resultSet.getString("address"),
				resultSet.getInt("metadata_attempts")
			))
			.list();
	}

	@Override
	public void saveResolution(Long complexId, ComplexMetadataResolution resolution, Instant nextAttemptAt) {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(resolution, "resolution is required");
		ComplexMetadata metadata = resolution.metadata();
		jdbcClient.sql("""
			WITH updated AS (
			    UPDATE complex
			    SET dong_cnt = COALESCE(dong_cnt, :dongCnt),
			        unit_cnt = COALESCE(unit_cnt, :unitCnt),
			        plat_area = COALESCE(plat_area, :platArea),
			        arch_area = COALESCE(arch_area, :archArea),
			        tot_area = COALESCE(tot_area, :totArea),
			        bc_rat = COALESCE(bc_rat, :bcRat),
			        vl_rat = COALESCE(vl_rat, :vlRat),
			        use_date = COALESCE(use_date, :useDate),
			        metadata_attempts = metadata_attempts + 1,
			        metadata_status = :metadataStatus,
			        metadata_source = :metadataSource,
			        metadata_checked_at = now(),
			        metadata_failure_reason = :metadataFailureReason,
			        metadata_failure_kind = :metadataFailureKind,
			        metadata_next_attempt_at = :metadataNextAttemptAt,
			        updated_at = now()
			    WHERE id = :complexId
			    RETURNING metadata_attempts
			)
			INSERT INTO complex_metadata_enrichment_attempt (
			    complex_id,
			    attempt_no,
			    status,
			    source,
			    failure_kind,
			    failure_reason,
			    next_attempt_at,
			    lookup_path,
			    requested_pnu,
			    resolved_source_pnu,
			    alias_id,
			    candidate_count
			)
			SELECT
			    :complexId,
			    updated.metadata_attempts,
			    :metadataStatus,
			    :metadataSource,
			    :metadataFailureKind,
			    :metadataFailureReason,
			    :metadataNextAttemptAt,
			    :lookupPath,
			    :requestedPnu,
			    :resolvedSourcePnu,
			    :aliasId,
			    :candidateCount
			FROM updated
			""")
			.param("complexId", complexId)
			.param("dongCnt", metadata == null ? null : metadata.dongCnt())
			.param("unitCnt", metadata == null ? null : metadata.unitCnt())
			.param("platArea", metadata == null ? null : metadata.platArea())
			.param("archArea", metadata == null ? null : metadata.archArea())
			.param("totArea", metadata == null ? null : metadata.totArea())
			.param("bcRat", metadata == null ? null : metadata.bcRat())
			.param("vlRat", metadata == null ? null : metadata.vlRat())
			.param("useDate", metadata == null ? null : metadata.useDate())
			.param("metadataStatus", resolution.status().name())
			.param("metadataSource", resolution.source())
			.param("metadataFailureReason", resolution.failureReason())
			.param("metadataFailureKind", resolution.failureKind() == null ? null : resolution.failureKind().name())
			.param("metadataNextAttemptAt", offsetDateTime(nextAttemptAt))
			.param("lookupPath", resolution.lookupEvidence().lookupPath().name())
			.param("requestedPnu", resolution.lookupEvidence().requestedPnu())
			.param("resolvedSourcePnu", resolution.lookupEvidence().resolvedSourcePnu())
			.param("aliasId", resolution.lookupEvidence().aliasId())
			.param("candidateCount", resolution.lookupEvidence().candidateCount())
			.update();
	}

	private OffsetDateTime offsetDateTime(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}
}
