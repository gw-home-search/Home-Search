package com.home.infrastructure.persistence.propertydetail;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.propertydetail.ComplexCenter;
import com.home.application.propertydetail.ComplexCenterReader;
import com.home.application.propertydetail.PropertyDetailReader;
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPropertyDetailReader implements PropertyDetailReader, ComplexCenterReader {

	private final JdbcClient jdbcClient;

	public JdbcPropertyDetailReader(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
		return jdbcClient.sql("""
			WITH redeveloped_parcel AS (
			    SELECT parcel_id
			    FROM complex_coordinate_case
			    WHERE relation_type = 'REDEVELOPED'
			      AND relation_confidence = 'HIGH'
			),
			superseded_complex AS (
			    SELECT c.id AS complex_id
			    FROM complex c
			    JOIN redeveloped_parcel rp ON rp.parcel_id = c.parcel_id
			    WHERE c.id <> (
			        SELECT c2.id
			        FROM complex c2
			        LEFT JOIN trade t2 ON t2.complex_id = c2.id AND t2.deleted_at IS NULL
			        WHERE c2.parcel_id = c.parcel_id
			        GROUP BY c2.id
			        ORDER BY
			            c2.use_date DESC NULLS LAST,
			            MAX(t2.deal_date) DESC NULLS LAST,
			            MIN(t2.deal_date) DESC NULLS LAST,
			            c2.id DESC
			        LIMIT 1
			    )
			)
			SELECT
			    p.id AS parcel_id,
			    c.id AS complex_id,
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
			    p.address,
			    c.display_name,
			    c.trade_name,
			    c.name,
			    c.dong_cnt,
			    c.unit_cnt,
			    c.plat_area,
			    c.arch_area,
			    c.tot_area,
			    c.bc_rat,
			    c.vl_rat,
			    c.use_date
			FROM parcel p
			JOIN complex c ON c.parcel_id = p.id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			LEFT JOIN superseded_complex sc ON sc.complex_id = c.id
			WHERE p.id = :parcelId
			  AND (CAST(:complexId AS BIGINT) IS NULL OR c.id = :complexId)
			ORDER BY (sc.complex_id IS NOT NULL), c.id
			LIMIT 1
			""")
			.param("parcelId", parcelId)
			.param("complexId", complexId)
			.query(this::mapParcelDetail)
			.optional();
	}

	@Override
	public Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
		if (!hasParcel(parcelId)) {
			return Optional.empty();
		}
		List<ComplexSummaryResult> complexes = jdbcClient.sql("""
			SELECT
			    c.id AS complex_id,
			    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
			    p.id AS parcel_id,
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
			    p.address,
			    c.dong_cnt,
			    c.unit_cnt,
			    c.use_date
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE p.id = :parcelId
			ORDER BY COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name), c.id
			""")
			.param("parcelId", parcelId)
			.query(this::mapComplexSummary)
			.list();
		return Optional.of(complexes);
	}

	@Override
	public Optional<ParcelDetailResult> findComplexDetail(Long complexId) {
		return jdbcClient.sql("""
			SELECT
			    p.id AS parcel_id,
			    c.id AS complex_id,
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
			    p.address,
			    c.display_name,
			    c.trade_name,
			    c.name,
			    c.dong_cnt,
			    c.unit_cnt,
			    c.plat_area,
			    c.arch_area,
			    c.tot_area,
			    c.bc_rat,
			    c.vl_rat,
			    c.use_date
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE c.id = :complexId
			""")
			.param("complexId", complexId)
			.query(this::mapParcelDetail)
			.optional();
	}

	@Override
	public Optional<ComplexCenter> findComplexCenter(Long complexId) {
		return jdbcClient.sql("""
			SELECT
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE c.id = :complexId
			""")
			.param("complexId", complexId)
			.query((resultSet, rowNumber) -> new ComplexCenter(
				doubleOrNull(resultSet, "latitude"),
				doubleOrNull(resultSet, "longitude")
			))
			.optional();
	}

	private boolean hasParcel(Long parcelId) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM parcel
			    WHERE id = :parcelId
			)
			""")
			.param("parcelId", parcelId)
			.query(Boolean.class)
			.single());
	}

	private ParcelDetailResult mapParcelDetail(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ParcelDetailResult(
			resultSet.getLong("parcel_id"),
			resultSet.getLong("complex_id"),
			doubleOrNull(resultSet, "latitude"),
			doubleOrNull(resultSet, "longitude"),
			resultSet.getString("address"),
			resultSet.getString("display_name"),
			resultSet.getString("trade_name"),
			resultSet.getString("name"),
			integerOrNull(resultSet, "dong_cnt"),
			integerOrNull(resultSet, "unit_cnt"),
			resultSet.getBigDecimal("plat_area"),
			resultSet.getBigDecimal("arch_area"),
			resultSet.getBigDecimal("tot_area"),
			resultSet.getBigDecimal("bc_rat"),
			resultSet.getBigDecimal("vl_rat"),
			resultSet.getObject("use_date", LocalDate.class)
		);
	}

	private ComplexSummaryResult mapComplexSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ComplexSummaryResult(
			resultSet.getLong("complex_id"),
			resultSet.getString("complex_name"),
			resultSet.getLong("parcel_id"),
			doubleOrNull(resultSet, "latitude"),
			doubleOrNull(resultSet, "longitude"),
			resultSet.getString("address"),
			integerOrNull(resultSet, "dong_cnt"),
			integerOrNull(resultSet, "unit_cnt"),
			resultSet.getObject("use_date", LocalDate.class)
		);
	}

	private Integer integerOrNull(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	private Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
		BigDecimal value = resultSet.getBigDecimal(column);
		return value == null ? null : value.doubleValue();
	}
}
