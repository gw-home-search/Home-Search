package com.home.infrastructure.persistence.complex;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.home.application.complex.ComplexRelationRepository;
import com.home.application.complex.ComplexTradeSpan;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexRelationRepository implements ComplexRelationRepository {

	private final JdbcClient jdbcClient;

	public JdbcComplexRelationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexTradeSpan> findTradeSpansByParcelId(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		return jdbcClient.sql("""
			SELECT
			    c.id AS complex_id,
			    c.complex_pk,
			    c.apt_seq,
			    c.name,
			    MIN(t.deal_date) AS first_deal,
			    MAX(t.deal_date) AS last_deal,
			    COUNT(t.id) AS trade_count,
			    c.use_date
			FROM complex c
			LEFT JOIN trade t ON t.complex_id = c.id
			    AND t.deleted_at IS NULL
			WHERE c.parcel_id = :parcelId
			GROUP BY c.id, c.complex_pk, c.apt_seq, c.name, c.use_date
			ORDER BY c.id
			""")
			.param("parcelId", parcelId)
			.query((resultSet, rowNumber) -> new ComplexTradeSpan(
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
}
