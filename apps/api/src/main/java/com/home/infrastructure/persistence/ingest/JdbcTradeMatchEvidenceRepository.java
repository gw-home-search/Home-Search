package com.home.infrastructure.persistence.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.matching.TradeMatchEvidenceCommand;
import com.home.application.ingest.matching.TradeMatchEvidenceRecord;
import com.home.application.ingest.matching.TradeMatchEvidenceRepository;
import com.home.domain.ingest.matching.TradeMatchStatus;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcTradeMatchEvidenceRepository implements TradeMatchEvidenceRepository {

	private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
	};

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public JdbcTradeMatchEvidenceRepository(JdbcClient jdbcClient) {
		this(jdbcClient, new ObjectMapper());
	}

	public JdbcTradeMatchEvidenceRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.objectMapper = Objects.requireNonNull(objectMapper);
	}

	@Override
	public TradeMatchEvidenceRecord save(TradeMatchEvidenceCommand command) {
		Objects.requireNonNull(command, "command is required");
		return jdbcClient.sql("""
			INSERT INTO trade_match_evidence (
			    raw_ingest_id,
			    source,
			    raw_jibun,
			    normalized_jibun,
			    sgg_cd,
			    umd_cd,
			    land_code,
			    bonbun,
			    bubun,
			    derived_pnu,
			    pnu_unavailable_reason,
			    apt_seq,
			    apt_name,
			    match_status,
			    match_path,
			    matched_complex_id,
			    matched_complex_pk,
			    candidate_count,
			    candidate_complex_ids,
			    failure_reason
			)
			VALUES (
			    :rawIngestId,
			    :source,
			    :rawJibun,
			    :normalizedJibun,
			    :sggCd,
			    :umdCd,
			    :landCode,
			    :bonbun,
			    :bubun,
			    :derivedPnu,
			    :pnuUnavailableReason,
			    :aptSeq,
			    :aptName,
			    :matchStatus,
			    :matchPath,
			    :matchedComplexId,
			    :matchedComplexPk,
			    :candidateCount,
			    CAST(:candidateComplexIds AS jsonb),
			    :failureReason
			)
			ON CONFLICT (raw_ingest_id) DO UPDATE
			SET source = EXCLUDED.source,
			    raw_jibun = EXCLUDED.raw_jibun,
			    normalized_jibun = EXCLUDED.normalized_jibun,
			    sgg_cd = EXCLUDED.sgg_cd,
			    umd_cd = EXCLUDED.umd_cd,
			    land_code = EXCLUDED.land_code,
			    bonbun = EXCLUDED.bonbun,
			    bubun = EXCLUDED.bubun,
			    derived_pnu = EXCLUDED.derived_pnu,
			    pnu_unavailable_reason = EXCLUDED.pnu_unavailable_reason,
			    apt_seq = EXCLUDED.apt_seq,
			    apt_name = EXCLUDED.apt_name,
			    match_status = EXCLUDED.match_status,
			    match_path = EXCLUDED.match_path,
			    matched_complex_id = EXCLUDED.matched_complex_id,
			    matched_complex_pk = EXCLUDED.matched_complex_pk,
			    candidate_count = EXCLUDED.candidate_count,
			    candidate_complex_ids = EXCLUDED.candidate_complex_ids,
			    failure_reason = EXCLUDED.failure_reason
			RETURNING *, candidate_complex_ids::text AS candidate_complex_ids_text
			""")
			.param("rawIngestId", command.rawIngestId())
			.param("source", command.source())
			.param("rawJibun", command.rawJibun())
			.param("normalizedJibun", command.normalizedJibun())
			.param("sggCd", command.sggCd())
			.param("umdCd", command.umdCd())
			.param("landCode", command.landCode())
			.param("bonbun", command.bonbun())
			.param("bubun", command.bubun())
			.param("derivedPnu", command.derivedPnu())
			.param("pnuUnavailableReason", command.pnuUnavailableReason())
			.param("aptSeq", command.aptSeq())
			.param("aptName", command.aptName())
			.param("matchStatus", command.matchStatus().name())
			.param("matchPath", command.matchPath())
			.param("matchedComplexId", command.matchedComplexId())
			.param("matchedComplexPk", command.matchedComplexPk())
			.param("candidateCount", command.candidateCount())
			.param("candidateComplexIds", candidateJson(command.candidateComplexIds()))
			.param("failureReason", command.failureReason())
			.query(this::mapRecord)
			.single();
	}

	@Override
	public Optional<TradeMatchEvidenceRecord> findByRawIngestId(Long rawIngestId) {
		return jdbcClient.sql("""
			SELECT *, candidate_complex_ids::text AS candidate_complex_ids_text
			FROM trade_match_evidence
			WHERE raw_ingest_id = :rawIngestId
			""")
			.param("rawIngestId", rawIngestId)
			.query(this::mapRecord)
			.optional();
	}

	private TradeMatchEvidenceRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
		return new TradeMatchEvidenceRecord(
			resultSet.getLong("id"),
			resultSet.getLong("raw_ingest_id"),
			resultSet.getString("source"),
			resultSet.getString("raw_jibun"),
			resultSet.getString("normalized_jibun"),
			resultSet.getString("sgg_cd"),
			resultSet.getString("umd_cd"),
			resultSet.getString("land_code"),
			resultSet.getString("bonbun"),
			resultSet.getString("bubun"),
			resultSet.getString("derived_pnu"),
			resultSet.getString("pnu_unavailable_reason"),
			resultSet.getString("apt_seq"),
			resultSet.getString("apt_name"),
			TradeMatchStatus.valueOf(resultSet.getString("match_status")),
			resultSet.getString("match_path"),
			longOrNull(resultSet, "matched_complex_id"),
			resultSet.getString("matched_complex_pk"),
			resultSet.getInt("candidate_count"),
			parseCandidateIds(resultSet.getString("candidate_complex_ids_text")),
			resultSet.getString("failure_reason"),
			instantOrNull(resultSet, "created_at")
		);
	}

	private String candidateJson(List<Long> candidateComplexIds) {
		try {
			return objectMapper.writeValueAsString(candidateComplexIds);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize trade match candidate ids", exception);
		}
	}

	private List<Long> parseCandidateIds(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, LONG_LIST);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to parse trade match candidate ids", exception);
		}
	}

	private Long longOrNull(ResultSet resultSet, String column) throws SQLException {
		long value = resultSet.getLong(column);
		return resultSet.wasNull() ? null : value;
	}

	private Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
		OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
