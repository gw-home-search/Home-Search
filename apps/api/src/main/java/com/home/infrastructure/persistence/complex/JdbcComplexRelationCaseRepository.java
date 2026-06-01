package com.home.infrastructure.persistence.complex;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.complex.ComplexRelationCaseMember;
import com.home.application.complex.ComplexRelationCaseRecord;
import com.home.application.complex.ComplexRelationCaseRepository;
import com.home.application.complex.ComplexRelationClassification;
import com.home.application.complex.ComplexRelationConfidence;
import com.home.application.complex.ComplexRelationType;
import com.home.application.complex.ComplexTradeSpan;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcComplexRelationCaseRepository implements ComplexRelationCaseRepository {

	private final JdbcClient jdbcClient;
	private final TransactionTemplate transactionTemplate;

	public JdbcComplexRelationCaseRepository(JdbcClient jdbcClient) {
		this(jdbcClient, null);
	}

	public JdbcComplexRelationCaseRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public ComplexRelationCaseRecord save(
		Long parcelId,
		ComplexRelationClassification classification,
		String classifierVersion
	) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		Objects.requireNonNull(classification, "classification is required");
		if (classifierVersion == null || classifierVersion.isBlank()) {
			throw new IllegalArgumentException("classifierVersion is required");
		}
		if (transactionTemplate == null) {
			return saveInTransaction(parcelId, classification, classifierVersion.trim());
		}
		return transactionTemplate.execute(status -> saveInTransaction(parcelId, classification, classifierVersion.trim()));
	}

	@Override
	public List<ComplexRelationCaseRecord> findByParcelId(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		List<CaseRow> cases = jdbcClient.sql("""
			SELECT
			    id,
			    case_key,
			    parcel_id,
			    pnu,
			    relation_type,
			    relation_confidence,
			    reason,
			    classifier_version,
			    evidence_json::text AS evidence_json
			FROM complex_relation_case
			WHERE parcel_id = :parcelId
			ORDER BY id
			""")
			.param("parcelId", parcelId)
			.query((resultSet, rowNumber) -> new CaseRow(
				resultSet.getLong("id"),
				resultSet.getString("case_key"),
				resultSet.getLong("parcel_id"),
				resultSet.getString("pnu"),
				ComplexRelationType.valueOf(resultSet.getString("relation_type")),
				ComplexRelationConfidence.valueOf(resultSet.getString("relation_confidence")),
				resultSet.getString("reason"),
				resultSet.getString("classifier_version"),
				resultSet.getString("evidence_json")
			))
			.list();
		return cases.stream()
			.map(row -> new ComplexRelationCaseRecord(
				row.id(),
				row.caseKey(),
				row.parcelId(),
				row.pnu(),
				row.relationType(),
				row.relationConfidence(),
				row.reason(),
				row.classifierVersion(),
				row.evidenceJson(),
				findMembers(row.id())
			))
			.toList();
	}

	private ComplexRelationCaseRecord saveInTransaction(
		Long parcelId,
		ComplexRelationClassification classification,
		String classifierVersion
	) {
		Long caseId = upsertCase(parcelId, classification, classifierVersion);
		deleteMembers(caseId);
		for (ComplexTradeSpan span : classification.spans()) {
			insertMember(caseId, span);
		}
		return findCase(caseId).orElseThrow(() -> new IllegalStateException("saved relation case was not found"));
	}

	private Long upsertCase(
		Long parcelId,
		ComplexRelationClassification classification,
		String classifierVersion
	) {
		return jdbcClient.sql("""
			INSERT INTO complex_relation_case (
			    case_key,
			    parcel_id,
			    pnu,
			    relation_type,
			    relation_confidence,
			    reason,
			    classifier_version,
			    evidence_json
			)
			SELECT
			    'complex-relation:' || p.pnu,
			    p.id,
			    p.pnu,
			    :relationType,
			    :relationConfidence,
			    :reason,
			    :classifierVersion,
			    jsonb_build_object(
			        'caseKey', 'complex-relation:' || p.pnu,
			        'relationType', :relationType,
			        'relationConfidence', :relationConfidence,
			        'reason', :reason,
			        'spanCount', :spanCount,
			        'classifierVersion', :classifierVersion
			    )
			FROM parcel p
			WHERE p.id = :parcelId
			ON CONFLICT (case_key) DO UPDATE SET
			    parcel_id = EXCLUDED.parcel_id,
			    pnu = EXCLUDED.pnu,
			    relation_type = EXCLUDED.relation_type,
			    relation_confidence = EXCLUDED.relation_confidence,
			    reason = EXCLUDED.reason,
			    classifier_version = EXCLUDED.classifier_version,
			    evidence_json = EXCLUDED.evidence_json,
			    checked_at = now()
			RETURNING id
			""")
			.param("parcelId", parcelId)
			.param("relationType", classification.type().name())
			.param("relationConfidence", classification.confidence().name())
			.param("reason", classification.reason())
			.param("classifierVersion", classifierVersion)
			.param("spanCount", classification.spans().size())
			.query(Long.class)
			.optional()
			.orElseThrow(() -> new IllegalArgumentException("parcel not found: " + parcelId));
	}

	private void deleteMembers(Long caseId) {
		jdbcClient.sql("DELETE FROM complex_relation_case_complex WHERE case_id = :caseId")
			.param("caseId", caseId)
			.update();
	}

	private void insertMember(Long caseId, ComplexTradeSpan span) {
		jdbcClient.sql("""
			INSERT INTO complex_relation_case_complex (
			    case_id,
			    complex_id,
			    complex_pk,
			    apt_seq,
			    name,
			    first_deal,
			    last_deal,
			    trade_count,
			    use_date
			)
			VALUES (
			    :caseId,
			    :complexId,
			    :complexPk,
			    :aptSeq,
			    :name,
			    :firstDeal,
			    :lastDeal,
			    :tradeCount,
			    :useDate
			)
			""")
			.param("caseId", caseId)
			.param("complexId", span.complexId())
			.param("complexPk", span.complexPk())
			.param("aptSeq", span.aptSeq())
			.param("name", span.name())
			.param("firstDeal", span.firstDeal())
			.param("lastDeal", span.lastDeal())
			.param("tradeCount", span.tradeCount())
			.param("useDate", span.useDate())
			.update();
	}

	private Optional<ComplexRelationCaseRecord> findCase(Long caseId) {
		return jdbcClient.sql("""
			SELECT
			    id,
			    case_key,
			    parcel_id,
			    pnu,
			    relation_type,
			    relation_confidence,
			    reason,
			    classifier_version,
			    evidence_json::text AS evidence_json
			FROM complex_relation_case
			WHERE id = :caseId
			""")
			.param("caseId", caseId)
			.query((resultSet, rowNumber) -> new ComplexRelationCaseRecord(
				resultSet.getLong("id"),
				resultSet.getString("case_key"),
				resultSet.getLong("parcel_id"),
				resultSet.getString("pnu"),
				ComplexRelationType.valueOf(resultSet.getString("relation_type")),
				ComplexRelationConfidence.valueOf(resultSet.getString("relation_confidence")),
				resultSet.getString("reason"),
				resultSet.getString("classifier_version"),
				resultSet.getString("evidence_json"),
				findMembers(caseId)
			))
			.optional();
	}

	private List<ComplexRelationCaseMember> findMembers(Long caseId) {
		return jdbcClient.sql("""
			SELECT
			    complex_id,
			    complex_pk,
			    apt_seq,
			    name,
			    first_deal,
			    last_deal,
			    trade_count,
			    use_date
			FROM complex_relation_case_complex
			WHERE case_id = :caseId
			ORDER BY id
			""")
			.param("caseId", caseId)
			.query((resultSet, rowNumber) -> new ComplexRelationCaseMember(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("apt_seq"),
				resultSet.getString("name"),
				toLocalDate(resultSet.getDate("first_deal")),
				toLocalDate(resultSet.getDate("last_deal")),
				resultSet.getLong("trade_count"),
				toLocalDate(resultSet.getDate("use_date"))
			))
			.list();
	}

	private LocalDate toLocalDate(Date value) {
		return value == null ? null : value.toLocalDate();
	}

	private record CaseRow(
		Long id,
		String caseKey,
		Long parcelId,
		String pnu,
		ComplexRelationType relationType,
		ComplexRelationConfidence relationConfidence,
		String reason,
		String classifierVersion,
		String evidenceJson
	) {
	}
}
