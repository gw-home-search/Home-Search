package com.home.infrastructure.persistence.map;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import com.home.application.map.ComplexMarkerRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcMapMarkerRepository implements ComplexMarkerRepository {

	private static final BigDecimal TRADE_AMOUNT_UNITS_PER_EOK = BigDecimal.valueOf(10_000L);
	private static final BigDecimal SQUARE_METERS_PER_PYEONG = new BigDecimal("3.305785");

	private final JdbcClient jdbcClient;

	public JdbcMapMarkerRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexMarkerResponse> findComplexMarkers(ComplexMarkersRequest request) {
		return jdbcClient.sql("""
			WITH parcel_complex AS (
			    SELECT
			        p.id AS parcel_id,
			        p.latitude AS lat,
			        p.longitude AS lng,
			        COALESCE(SUM(c.unit_cnt), 0) AS unit_cnt_sum,
			        MAX(
			            CASE
			                WHEN c.use_date IS NULL THEN NULL
			                ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, c.use_date))
			            END
			        ) AS building_age
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE (
			        (
			            p.geom IS NOT NULL
			            AND ST_Intersects(
			                p.geom,
			                ST_MakeEnvelope(
			                    CAST(:swLng AS DOUBLE PRECISION),
			                    CAST(:swLat AS DOUBLE PRECISION),
			                    CAST(:neLng AS DOUBLE PRECISION),
			                    CAST(:neLat AS DOUBLE PRECISION),
			                    4326
			                )
			            )
			        )
			        OR (
			            p.geom IS NULL
			            AND p.latitude BETWEEN :swLat AND :neLat
			            AND p.longitude BETWEEN :swLng AND :neLng
			        )
			    )
			    GROUP BY p.id, p.latitude, p.longitude
			),
			latest_trade AS (
			    SELECT DISTINCT ON (c.parcel_id)
			        c.parcel_id,
			        t.deal_amount,
			        t.excl_area
			    FROM complex c
			    JOIN trade t ON t.complex_id = c.id
			    WHERE t.deleted_at IS NULL
			    ORDER BY c.parcel_id, t.deal_date DESC, t.id DESC
			)
			SELECT
			    pc.parcel_id,
			    pc.lat,
			    pc.lng,
			    lt.deal_amount AS latest_deal_amount,
			    pc.unit_cnt_sum
			FROM parcel_complex pc
			LEFT JOIN latest_trade lt ON lt.parcel_id = pc.parcel_id
			WHERE (CAST(:unitMin AS BIGINT) IS NULL OR pc.unit_cnt_sum >= :unitMin)
			  AND (CAST(:unitMax AS BIGINT) IS NULL OR pc.unit_cnt_sum <= :unitMax)
			  AND (CAST(:priceMin AS NUMERIC) IS NULL OR lt.deal_amount >= :priceMin)
			  AND (CAST(:priceMax AS NUMERIC) IS NULL OR lt.deal_amount <= :priceMax)
			  AND (CAST(:areaMin AS NUMERIC) IS NULL OR lt.excl_area >= :areaMin)
			  AND (CAST(:areaMax AS NUMERIC) IS NULL OR lt.excl_area <= :areaMax)
			  AND (CAST(:ageMin AS INTEGER) IS NULL OR pc.building_age >= :ageMin)
			  AND (CAST(:ageMax AS INTEGER) IS NULL OR pc.building_age <= :ageMax)
			ORDER BY pc.parcel_id
			""")
			.param("swLat", request.swLat())
			.param("swLng", request.swLng())
			.param("neLat", request.neLat())
			.param("neLng", request.neLng())
			.param("unitMin", request.unitMin())
			.param("unitMax", request.unitMax())
			.param("priceMin", eokToTradeAmount(request.priceEokMin()))
			.param("priceMax", eokToTradeAmount(request.priceEokMax()))
			.param("areaMin", pyeongToSquareMeters(request.pyeongMin()))
			.param("areaMax", pyeongToSquareMeters(request.pyeongMax()))
			.param("ageMin", request.ageMin())
			.param("ageMax", request.ageMax())
			.query(this::mapMarker)
			.list();
	}

	private ComplexMarkerResponse mapMarker(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ComplexMarkerResponse(
			resultSet.getLong("parcel_id"),
			resultSet.getDouble("lat"),
			resultSet.getDouble("lng"),
			longOrNull(resultSet, "latest_deal_amount"),
			resultSet.getLong("unit_cnt_sum")
		);
	}

	private Long longOrNull(ResultSet resultSet, String columnName) throws SQLException {
		long value = resultSet.getLong(columnName);
		return resultSet.wasNull() ? null : value;
	}

	private BigDecimal eokToTradeAmount(Double eok) {
		return eok == null ? null : BigDecimal.valueOf(eok).multiply(TRADE_AMOUNT_UNITS_PER_EOK);
	}

	private BigDecimal pyeongToSquareMeters(Integer pyeong) {
		return pyeong == null ? null : BigDecimal.valueOf(pyeong).multiply(SQUARE_METERS_PER_PYEONG);
	}
}
