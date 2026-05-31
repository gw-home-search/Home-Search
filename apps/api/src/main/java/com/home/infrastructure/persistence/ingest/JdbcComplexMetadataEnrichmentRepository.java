package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Objects;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataEnrichmentRepository;
import com.home.application.ingest.ComplexMetadataLookup;
import com.home.application.ingest.ComplexMetadataResolution;

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
			    p.address
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE c.metadata_status = 'PENDING'
			ORDER BY c.id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new ComplexMetadataLookup(
				resultSet.getLong("id"),
				resultSet.getString("apt_seq"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				resultSet.getString("address")
			))
			.list();
	}

	@Override
	public void saveResolution(Long complexId, ComplexMetadataResolution resolution) {
		Objects.requireNonNull(complexId, "complexId is required");
		Objects.requireNonNull(resolution, "resolution is required");
		ComplexMetadata metadata = resolution.metadata();
		jdbcClient.sql("""
			UPDATE complex
			SET dong_cnt = COALESCE(dong_cnt, :dongCnt),
			    unit_cnt = COALESCE(unit_cnt, :unitCnt),
			    plat_area = COALESCE(plat_area, :platArea),
			    arch_area = COALESCE(arch_area, :archArea),
			    tot_area = COALESCE(tot_area, :totArea),
			    bc_rat = COALESCE(bc_rat, :bcRat),
			    vl_rat = COALESCE(vl_rat, :vlRat),
			    use_date = COALESCE(use_date, :useDate),
			    metadata_status = :metadataStatus,
			    metadata_source = :metadataSource,
			    metadata_checked_at = now(),
			    metadata_failure_reason = :metadataFailureReason,
			    updated_at = now()
			WHERE id = :complexId
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
			.update();
	}
}
