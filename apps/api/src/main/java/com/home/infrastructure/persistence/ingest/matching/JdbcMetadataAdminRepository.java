package com.home.infrastructure.persistence.ingest.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.home.application.ingest.metadata.admin.InvalidMetadataAdminRequestException;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Attempt;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Decision;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;
import com.home.application.ingest.metadata.admin.MetadataAdminRepository;
import com.home.domain.complex.metadata.OdcloudPnuAliasStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcMetadataAdminRepository implements MetadataAdminRepository {
	private final JdbcClient jdbcClient;
	private final TransactionTemplate transactionTemplate;

	public JdbcMetadataAdminRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
	}

	@Override
	public List<Pending> findPending(int limit, int offset) {
		return jdbcClient.sql("""
			SELECT c.id, COALESCE(c.trade_name, c.name) apt_name, c.apt_seq, p.pnu, p.address,
			       c.metadata_status, c.metadata_failure_kind, c.metadata_failure_reason, c.metadata_attempts,
			       c.metadata_next_attempt_at, c.metadata_hold_at, c.metadata_hold_reason
			FROM complex c JOIN parcel p ON p.id = c.parcel_id
			WHERE c.metadata_status <> 'RESOLVED' OR c.metadata_hold_at IS NOT NULL
			ORDER BY c.metadata_hold_at DESC NULLS LAST, c.metadata_next_attempt_at NULLS LAST, c.id
			LIMIT :limit OFFSET :offset
			""").param("limit", limit).param("offset", offset).query(this::pending).list();
	}

	@Override
	public Summary summary() {
		List<Map.Entry<String, Long>> rows = jdbcClient.sql("""
			SELECT metadata_status, count(*) count
			FROM complex
			WHERE metadata_status <> 'RESOLVED' OR metadata_hold_at IS NOT NULL
			GROUP BY metadata_status
			""").query((rs, n) -> Map.entry(rs.getString("metadata_status"), rs.getLong("count"))).list();
		Map<String, Long> counts = rows.stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		return new Summary(counts.values().stream().mapToLong(Long::longValue).sum(), counts);
	}

	@Override
	public Detail detail(long complexId) {
		Pending complex = jdbcClient.sql("""
			SELECT c.id, COALESCE(c.trade_name, c.name) apt_name, c.apt_seq, p.pnu, p.address,
			       c.metadata_status, c.metadata_failure_kind, c.metadata_failure_reason, c.metadata_attempts,
			       c.metadata_next_attempt_at, c.metadata_hold_at, c.metadata_hold_reason
			FROM complex c JOIN parcel p ON p.id = c.parcel_id WHERE c.id=:id
			""").param("id", complexId).query(this::pending).optional()
			.orElseThrow(() -> new InvalidMetadataAdminRequestException("complex not found"));
		List<Attempt> attempts = jdbcClient.sql("""
			SELECT attempt_no,status,source,failure_kind,failure_reason,lookup_path,requested_pnu,resolved_source_pnu,
			       alias_id,candidate_count,next_attempt_at,observed_at
			FROM complex_metadata_enrichment_attempt WHERE complex_id=:id ORDER BY observed_at DESC,id DESC
			""").param("id", complexId).query((rs, n) -> new Attempt(rs.getInt("attempt_no"), rs.getString("status"),
				rs.getString("source"), rs.getString("failure_kind"), rs.getString("failure_reason"), rs.getString("lookup_path"),
				rs.getString("requested_pnu"), rs.getString("resolved_source_pnu"), rs.getObject("alias_id", Long.class),
				rs.getObject("candidate_count", Integer.class), rs.getObject("next_attempt_at", java.time.OffsetDateTime.class),
				rs.getObject("observed_at", java.time.OffsetDateTime.class))).list();
		List<Decision> decisions = jdbcClient.sql("""
			SELECT decision_type,actor,reason,created_at FROM complex_metadata_admin_decision
			WHERE complex_id=:id ORDER BY created_at DESC,id DESC
			""").param("id", complexId).query((rs,n) -> new Decision(rs.getString("decision_type"), rs.getString("actor"),
				rs.getString("reason"), rs.getObject("created_at", java.time.OffsetDateTime.class))).list();
		return new Detail(complex, attempts, decisions);
	}

	@Override public ActionResult retry(long id, String actor, String reason) {
		return complexDecision(id, actor, reason, "RETRY_REQUESTED", """
			UPDATE complex SET metadata_hold_at=NULL, metadata_hold_reason=NULL, metadata_hold_by=NULL,
			metadata_next_attempt_at=now(), updated_at=now() WHERE id=:id
			""");
	}
	@Override public ActionResult hold(long id, String actor, String reason) {
		return complexDecision(id, actor, reason, "HOLD", """
			UPDATE complex SET metadata_hold_at=now(), metadata_hold_reason=:reason, metadata_hold_by=:actor,
			metadata_next_attempt_at=NULL, updated_at=now() WHERE id=:id
			""");
	}
	private ActionResult complexDecision(long id, String actor, String reason, String type, String update) {
		Boolean updated = transactionTemplate.execute(status -> {
			int count = jdbcClient.sql(update).param("id", id).param("actor", actor).param("reason", reason).update();
			if (count == 0) throw new InvalidMetadataAdminRequestException("complex not found");
			insertDecision("complex_id", id, type, actor, reason);
			return true;
		});
		return new ActionResult(Boolean.TRUE.equals(updated));
	}
	@Override public List<Alias> aliases() {
		return jdbcClient.sql("""
			SELECT id,canonical_prefix,source_prefix,status,reason,approved_by,approved_at,disabled_by,disabled_at
			FROM odcloud_pnu_prefix_alias ORDER BY id
			""").query(this::alias).list();
	}
	@Override public Alias proposeAlias(String canonical, String source, String actor, String reason) {
		return transactionTemplate.execute(status -> {
			Alias alias = jdbcClient.sql("""
				INSERT INTO odcloud_pnu_prefix_alias(canonical_prefix,source_prefix,status,reason,requested_by)
				VALUES(:canonical,:source,'PENDING',:reason,:actor)
				ON CONFLICT (canonical_prefix,source_prefix) DO NOTHING
				RETURNING id,canonical_prefix,source_prefix,status,reason,approved_by,approved_at,disabled_by,disabled_at
				""").param("canonical", canonical).param("source", source).param("reason", reason).param("actor", actor)
				.query(this::alias).optional()
				.orElseThrow(() -> new InvalidMetadataAdminRequestException("alias already exists"));
			insertDecision("alias_id", alias.id(), "ALIAS_PROPOSED", actor, reason);
			return alias;
		});
	}
	@Override public ActionResult approveAlias(long id, String actor, String reason) {
		return aliasDecision(id, actor, reason, "ALIAS_APPROVED", "APPROVED");
	}
	@Override public ActionResult disableAlias(long id, String actor, String reason) {
		return aliasDecision(id, actor, reason, "ALIAS_DISABLED", "DISABLED");
	}
	private ActionResult aliasDecision(long id, String actor, String reason, String type, String target) {
		Boolean updated = transactionTemplate.execute(status -> {
			if (target.equals("APPROVED") && hasAnotherApprovedAlias(id)) {
				throw new InvalidMetadataAdminRequestException("canonical prefix already has an approved alias");
			}
			String extra = target.equals("APPROVED") ? "approved_by=:actor,approved_at=now()" : "disabled_by=:actor,disabled_at=now()";
			String allowedStatus = target.equals("APPROVED") ? "status='PENDING'" : "status IN ('PENDING','APPROVED')";
			int count = jdbcClient.sql("UPDATE odcloud_pnu_prefix_alias SET status=:target, " + extra
					+ ", updated_at=now() WHERE id=:id AND " + allowedStatus)
				.param("target", target).param("actor", actor).param("id", id).update();
			if (count == 0) throw new InvalidMetadataAdminRequestException("alias transition unavailable");
			if (target.equals("APPROVED")) {
				jdbcClient.sql("""
					UPDATE complex c
					SET metadata_next_attempt_at=now(), updated_at=now()
					FROM parcel p, odcloud_pnu_prefix_alias alias
					WHERE c.parcel_id=p.id AND alias.id=:id
					  AND substring(p.pnu,1,8)=alias.canonical_prefix
					  AND c.metadata_status IN ('UNAVAILABLE','FAILED','PARTIAL')
					  AND c.metadata_hold_at IS NULL
					""").param("id", id).update();
			}
			insertDecision("alias_id", id, type, actor, reason);
			return true;
		});
		return new ActionResult(Boolean.TRUE.equals(updated));
	}
	private boolean hasAnotherApprovedAlias(long id) {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM odcloud_pnu_prefix_alias candidate
			    JOIN odcloud_pnu_prefix_alias target ON target.id=:id
			    WHERE candidate.canonical_prefix=target.canonical_prefix
			      AND candidate.status='APPROVED'
			      AND candidate.id<>target.id
			)
			""").param("id", id).query(Boolean.class).single();
	}
	private void insertDecision(String targetColumn, long targetId, String type, String actor, String reason) {
		jdbcClient.sql("INSERT INTO complex_metadata_admin_decision(" + targetColumn + ",decision_type,actor,reason) VALUES(:id,:type,:actor,:reason)")
			.param("id", targetId).param("type", type).param("actor", actor).param("reason", reason).update();
	}
	private Pending pending(ResultSet rs, int n) throws SQLException {
		return new Pending(rs.getLong("id"),rs.getString("apt_name"),rs.getString("apt_seq"),rs.getString("pnu"),
			rs.getString("address"),rs.getString("metadata_status"),rs.getString("metadata_failure_kind"),
			rs.getString("metadata_failure_reason"),rs.getInt("metadata_attempts"),
			rs.getObject("metadata_next_attempt_at",java.time.OffsetDateTime.class),rs.getObject("metadata_hold_at",java.time.OffsetDateTime.class),
			rs.getString("metadata_hold_reason"));
	}
	private Alias alias(ResultSet rs, int n) throws SQLException {
		return new Alias(rs.getLong("id"),rs.getString("canonical_prefix").trim(),rs.getString("source_prefix").trim(),
			OdcloudPnuAliasStatus.valueOf(rs.getString("status")),rs.getString("reason"),rs.getString("approved_by"),
			rs.getObject("approved_at",java.time.OffsetDateTime.class),rs.getString("disabled_by"),
			rs.getObject("disabled_at",java.time.OffsetDateTime.class));
	}
}
