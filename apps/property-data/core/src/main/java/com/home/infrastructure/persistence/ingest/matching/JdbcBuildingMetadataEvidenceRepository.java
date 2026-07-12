package com.home.infrastructure.persistence.ingest.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.home.application.ingest.buildingmetadata.BuildingMetadataAttemptResult;
import com.home.application.ingest.buildingmetadata.BuildingMetadataEvidenceRepository;
import com.home.application.ingest.buildingmetadata.BuildingMetadataTarget;
import com.home.application.ingest.buildingmetadata.ParsedBuildingMetadataSource;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.InternalCandidate;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.InternalName;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.MatchResult;
import com.home.domain.complex.buildingmetadata.BuildingMetadataMatchPolicy.SourceCandidate;
import com.home.domain.complex.buildingmetadata.BuildingMetadataProjectionPolicy;
import com.home.domain.complex.buildingmetadata.BuildingMetadataProjectionPolicy.ProjectionDecision;
import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.buildingmetadata.BuildingMetadataValues;
import com.home.domain.complex.buildingmetadata.ComplexNameNormalizer;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcBuildingMetadataEvidenceRepository implements BuildingMetadataEvidenceRepository {
	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final BuildingMetadataMatchPolicy matchPolicy = new BuildingMetadataMatchPolicy();
	private final BuildingMetadataProjectionPolicy projectionPolicy = new BuildingMetadataProjectionPolicy();

	public JdbcBuildingMetadataEvidenceRepository(JdbcClient jdbc, TransactionTemplate transaction) {
		this.jdbc = Objects.requireNonNull(jdbc);
		this.transaction = Objects.requireNonNull(transaction);
	}

	@Override
	public List<BuildingMetadataTarget> findTargets(String mode, int limit, Long fromId, Long toId, UUID requestId) {
		String modePredicate = switch (mode) {
			case "missing" -> """
				NOT EXISTS (SELECT 1 FROM complex_metadata_enrichment_attempt a
				 WHERE a.complex_id=c.id AND a.source IN ('BLD_TITLE','BLD_RECAP_TITLE'))
				""";
			case "retry" -> """
				EXISTS (SELECT 1 FROM complex_metadata_enrichment_attempt a WHERE a.id=(
				 SELECT x.id FROM complex_metadata_enrichment_attempt x
				 WHERE x.complex_id=c.id AND x.source IN ('BLD_TITLE','BLD_RECAP_TITLE')
				 ORDER BY x.observed_at DESC,x.id DESC LIMIT 1)
				 AND a.status IN ('PARTIAL','UNAVAILABLE','FAILED') AND a.next_attempt_at<=now())
				""";
			default -> throw new IllegalArgumentException("mode must be missing or retry");
		};
		return jdbc.sql("""
			SELECT c.id,p.pnu,c.bld_mgm_bld_rgst_pk,c.dong_cnt,c.unit_cnt,c.plat_area,c.arch_area,
			       c.tot_area,c.bc_rat,c.vl_rat,c.use_date,
			       (SELECT count(*) FROM complex pc JOIN parcel pp ON pp.id=pc.parcel_id WHERE pp.pnu=p.pnu) AS pnu_complex_count
			FROM complex c JOIN parcel p ON p.id=c.parcel_id
			WHERE (c.bld_mgm_bld_rgst_pk IS NULL OR c.plat_area IS NULL OR c.arch_area IS NULL
			       OR c.tot_area IS NULL OR c.bc_rat IS NULL OR c.vl_rat IS NULL)
			  AND c.metadata_hold_at IS NULL
			  AND c.id>=COALESCE(:from_id,c.id) AND c.id<=COALESCE(:to_id,c.id)
			  AND NOT EXISTS (SELECT 1 FROM complex_metadata_enrichment_attempt request_attempt
			                  WHERE request_attempt.complex_id=c.id AND request_attempt.request_id=:request_id
			                    AND request_attempt.source IN ('BLD_TITLE','BLD_RECAP_TITLE'))
			  AND (""" + modePredicate + ") ORDER BY c.id LIMIT :limit")
			.param("from_id", fromId).param("to_id", toId).param("request_id", requestId)
			.param("limit", limit).query(this::target).list();
	}

	@Override
	public BuildingMetadataAttemptResult recordAmbiguousPnu(BuildingMetadataTarget target, UUID requestId) {
		BuildingMetadataSourceKind source = target.currentValues().dongCnt() != null
			&& target.currentValues().dongCnt() == 1 ? BuildingMetadataSourceKind.BLD_TITLE
			: BuildingMetadataSourceKind.BLD_RECAP_TITLE;
		return transaction.execute(status -> record(target, source, ComplexMetadataStatus.AMBIGUOUS,
			ComplexMetadataFailureKind.AMBIGUOUS, "multiple complexes share the requested PNU", null,
			target.pnuComplexCount(), false, requestId, null));
	}

	@Override
	public BuildingMetadataAttemptResult apply(BuildingMetadataTarget target, BuildingMetadataSourceKind source,
		ParsedBuildingMetadataSource parsed, UUID requestId) {
		return transaction.execute(tx -> applyLocked(target, source, parsed, requestId));
	}

	@Override
	public BuildingMetadataAttemptResult recordFailure(BuildingMetadataTarget target, BuildingMetadataSourceKind source,
		ComplexMetadataStatus status, ComplexMetadataFailureKind kind, String reason, UUID requestId, Instant nextAt) {
		return transaction.execute(tx -> record(target, source, status, kind, reason, null, null, false, requestId, nextAt));
	}

	private BuildingMetadataAttemptResult applyLocked(BuildingMetadataTarget target, BuildingMetadataSourceKind source,
		ParsedBuildingMetadataSource parsed, UUID requestId) {
		LockedComplex locked = lock(target.complexId());
		if (!locked.pnu().equals(target.pnu()) || pnuCount(locked.pnu()) != 1) {
			return record(target, source, ComplexMetadataStatus.AMBIGUOUS, ComplexMetadataFailureKind.AMBIGUOUS,
				"PNU changed or is no longer unique", null, parsed.candidates().size(), false, requestId, null);
		}
		if (parsed.candidates().size() != 1) {
			return record(target, source, ComplexMetadataStatus.AMBIGUOUS, ComplexMetadataFailureKind.AMBIGUOUS,
				"building source returned multiple candidates", null, parsed.candidates().size(), false, requestId, null);
		}
		SourceCandidate candidate = parsed.candidates().get(0);
		if (candidate.sourceKey() == null || candidate.sourceKey().isBlank()) {
			return record(target, source, ComplexMetadataStatus.FAILED, ComplexMetadataFailureKind.PERMANENT,
				"building register management key is missing", null, 1, false, requestId, null);
		}
		if (locked.key() != null && !locked.key().equals(candidate.sourceKey())) {
			return conflict(target, source, "existing building register identity conflicts", requestId);
		}
		Long owner = jdbc.sql("SELECT id FROM complex WHERE bld_mgm_bld_rgst_pk=:key")
			.param("key", candidate.sourceKey()).query(Long.class).optional().orElse(null);
		if (owner != null && owner != target.complexId()) return conflict(target, source,
			"building register identity belongs to another complex", requestId);

		InternalCandidate internal = new InternalCandidate(target.complexId(), locked.pnu(), names(target.complexId(), locked), locked.values());
		MatchResult match = matchPolicy.resolveBuilding(locked.pnu(), List.of(internal), List.of(candidate));
		if (!match.status().isResolvedLike()) return conflict(target, source,
			match.failureReason() == null ? "building candidate identity is ambiguous" : match.failureReason(), requestId);
		ProjectionDecision projection = projectionPolicy.decide(locked.values(), candidate.values());
		if (!projection.apply()) return conflict(target, source, "existing metadata conflicts with building source", requestId);

		BuildingMetadataValues merged = projection.values();
		boolean changed = !locked.values().equals(merged) || locked.key() == null;
		String metadataStatus = coreComplete(merged) ? "RESOLVED" : "PARTIAL";
		int updated = jdbc.sql("""
			UPDATE complex SET dong_cnt=COALESCE(dong_cnt,:dong),unit_cnt=COALESCE(unit_cnt,:unit),
			 plat_area=COALESCE(plat_area,:plat),arch_area=COALESCE(arch_area,:arch),tot_area=COALESCE(tot_area,:tot),
			 bc_rat=COALESCE(bc_rat,:bc),vl_rat=COALESCE(vl_rat,:vl),use_date=COALESCE(use_date,:use_date),
			 bld_mgm_bld_rgst_pk=COALESCE(bld_mgm_bld_rgst_pk,:key),metadata_status=:metadata_status,
			 metadata_source=CASE WHEN :changed THEN :source ELSE metadata_source END,
			 metadata_checked_at=now(),metadata_failure_kind=NULL,metadata_failure_reason=NULL,
			 metadata_next_attempt_at=NULL,updated_at=CASE WHEN :changed THEN now() ELSE updated_at END
			WHERE id=:id AND (bld_mgm_bld_rgst_pk IS NULL OR bld_mgm_bld_rgst_pk=:key)
			""").param("dong", merged.dongCnt()).param("unit", merged.unitCnt()).param("plat", merged.platArea())
			.param("arch", merged.archArea()).param("tot", merged.totArea()).param("bc", merged.bcRat())
			.param("vl", merged.vlRat()).param("use_date", merged.useDate()).param("key", candidate.sourceKey())
			.param("metadata_status", metadataStatus).param("source", source.name()).param("changed", changed)
			.param("id", target.complexId()).update();
		if (updated != 1) return conflict(target, source, "building register identity changed concurrently", requestId);
		String alias = candidate.names().stream().filter(name -> name != null && !name.isBlank()).findFirst().orElse(null);
		if (alias != null) upsertAlias(target.complexId(), alias);
		ComplexMetadataStatus attemptStatus = coreComplete(merged) && merged.hasAllAreaValues()
			? ComplexMetadataStatus.RESOLVED : ComplexMetadataStatus.PARTIAL;
		Instant retry = attemptStatus.isPartial() ? Instant.now().plusSeconds(14L * 86_400) : null;
		return record(target, source, attemptStatus, null, null, locked.pnu(), 1, changed, requestId, retry);
	}

	private BuildingMetadataAttemptResult conflict(BuildingMetadataTarget target, BuildingMetadataSourceKind source,
		String reason, UUID requestId) {
		return record(target, source, ComplexMetadataStatus.AMBIGUOUS, ComplexMetadataFailureKind.AMBIGUOUS,
			reason, null, 1, false, requestId, null);
	}

	private BuildingMetadataAttemptResult record(BuildingMetadataTarget target, BuildingMetadataSourceKind source,
		ComplexMetadataStatus status, ComplexMetadataFailureKind kind, String reason, String resolvedPnu,
		Integer candidates, boolean applied, UUID requestId, Instant nextAt) {
		LockedComplex locked = lock(target.complexId());
		int attemptNo = jdbc.sql("UPDATE complex SET metadata_attempts=metadata_attempts+1,metadata_checked_at=now()," +
			"metadata_failure_kind=:kind,metadata_failure_reason=:reason,metadata_next_attempt_at=:next_at," +
			"metadata_status=CASE WHEN metadata_status='RESOLVED' THEN metadata_status ELSE :status END " +
			"WHERE id=:id RETURNING metadata_attempts")
			.param("kind", kind == null ? null : kind.name()).param("reason", reason)
			.param("next_at", offset(nextAt)).param("status", status.name()).param("id", target.complexId())
			.query(Integer.class).single();
		jdbc.sql("""
			INSERT INTO complex_metadata_enrichment_attempt
			(complex_id,attempt_no,status,source,failure_kind,failure_reason,next_attempt_at,lookup_path,
			 requested_pnu,resolved_source_pnu,candidate_count,request_id,projection_applied)
			VALUES (:id,:no,:status,:source,:kind,:reason,:next_at,'BUILDING_PNU',:pnu,:resolved,:count,:request_id,:applied)
			""").param("id", target.complexId()).param("no", attemptNo).param("status", status.name())
			.param("source", source == null ? "BLD_RECAP_TITLE" : source.name()).param("kind", kind == null ? null : kind.name())
			.param("reason", reason).param("next_at", offset(nextAt)).param("pnu", locked.pnu())
			.param("resolved", resolvedPnu).param("count", candidates).param("request_id", requestId)
			.param("applied", applied).update();
		return new BuildingMetadataAttemptResult(status, applied);
	}

	private LockedComplex lock(long id) {
		return jdbc.sql("""
			SELECT c.id,p.pnu,c.name,c.trade_name,c.bld_mgm_bld_rgst_pk,c.dong_cnt,c.unit_cnt,c.plat_area,
			 c.arch_area,c.tot_area,c.bc_rat,c.vl_rat,c.use_date FROM complex c JOIN parcel p ON p.id=c.parcel_id
			 WHERE c.id=:id FOR UPDATE OF c
			""").param("id", id).query(this::locked).single();
	}

	private int pnuCount(String pnu) { return jdbc.sql("SELECT count(*) FROM complex c JOIN parcel p ON p.id=c.parcel_id WHERE p.pnu=:pnu")
		.param("pnu", pnu).query(Integer.class).single(); }
	private List<InternalName> names(long id, LockedComplex locked) {
		List<InternalName> aliases = jdbc.sql("""
			SELECT alias_type,alias_name FROM complex_name_alias WHERE complex_id=:id
			 AND alias_type IN ('BUILDING_REGISTER_NAME','ADMIN_ALIAS','RTMS_APT_NAME','SOURCE_ALIAS')
			""").param("id", id).query((rs,n) -> new InternalName(rs.getString(1),rs.getString(2),1)).list();
		java.util.ArrayList<InternalName> result = new java.util.ArrayList<>(aliases);
		result.add(new InternalName("NAME", locked.name(), 2));
		if (locked.tradeName() != null) result.add(new InternalName("TRADE_NAME", locked.tradeName(), 3));
		return result;
	}
	private void upsertAlias(long id, String alias) { jdbc.sql("""
		INSERT INTO complex_name_alias(complex_id,alias_type,alias_name,normalized_name,source,source_key)
		VALUES (:id,'BUILDING_REGISTER_NAME',:name,:normalized,'BLD',NULL)
		ON CONFLICT (complex_id,alias_type,normalized_name) DO UPDATE SET last_seen_at=now(),updated_at=now()
		""").param("id",id).param("name",alias).param("normalized",ComplexNameNormalizer.normalize(alias)).update(); }
	private boolean coreComplete(BuildingMetadataValues value) { return value.dongCnt()!=null && value.unitCnt()!=null && value.useDate()!=null; }
	private OffsetDateTime offset(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

	private BuildingMetadataTarget target(ResultSet rs,int row) throws SQLException { return new BuildingMetadataTarget(rs.getLong("id"),rs.getString("pnu"),rs.getInt("pnu_complex_count"),rs.getString("bld_mgm_bld_rgst_pk"),values(rs)); }
	private LockedComplex locked(ResultSet rs,int row) throws SQLException { return new LockedComplex(rs.getLong("id"),rs.getString("pnu"),rs.getString("name"),rs.getString("trade_name"),rs.getString("bld_mgm_bld_rgst_pk"),values(rs)); }
	private BuildingMetadataValues values(ResultSet rs) throws SQLException { return new BuildingMetadataValues((Integer)rs.getObject("dong_cnt"),(Integer)rs.getObject("unit_cnt"),rs.getBigDecimal("plat_area"),rs.getBigDecimal("arch_area"),rs.getBigDecimal("tot_area"),rs.getBigDecimal("bc_rat"),rs.getBigDecimal("vl_rat"),rs.getObject("use_date", LocalDate.class)); }
	private record LockedComplex(long id,String pnu,String name,String tradeName,String key,BuildingMetadataValues values) {}
}
