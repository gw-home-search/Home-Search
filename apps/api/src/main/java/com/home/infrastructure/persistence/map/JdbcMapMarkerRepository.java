package com.home.infrastructure.persistence.map;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcMapMarkerRepository implements ComplexMarkerRepository {

	private static final BigDecimal TRADE_AMOUNT_UNITS_PER_EOK = BigDecimal.valueOf(10_000L);
	private static final BigDecimal SQUARE_METERS_PER_PYEONG = new BigDecimal("3.305785");
	private static final int TRUSTED_BUILDING_COORDINATE_CONFIDENCE = 80;

	private final JdbcClient jdbcClient;

	public JdbcMapMarkerRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query) {
		if (hasMarkerShapeFilter(query)) {
			return findComplexMarkersWithMarkerShapeFilter(query);
		}
		return findComplexMarkersWithTradeFirst(query);
	}

	private List<ComplexMarkerResult> findComplexMarkersWithMarkerShapeFilter(ComplexMarkerQuery query) {
		return jdbcClient.sql("""
			WITH requested_bounds AS (
			    SELECT ST_MakeEnvelope(
			        CAST(:swLng AS DOUBLE PRECISION),
			        CAST(:swLat AS DOUBLE PRECISION),
			        CAST(:neLng AS DOUBLE PRECISION),
			        CAST(:neLat AS DOUBLE PRECISION),
			        4326
			    ) AS geom
			),
			bounded_parcel AS (
			    SELECT p.*
			    FROM parcel p
			    CROSS JOIN requested_bounds bounds
			    WHERE (
			        p.geom IS NOT NULL
			        AND ST_Intersects(p.geom, bounds.geom)
			    )
			       OR (
			           p.geom IS NULL
			           AND p.latitude BETWEEN :swLat AND :neLat
			           AND p.longitude BETWEEN :swLng AND :neLng
			       )
			),
			complex_base AS (
			    SELECT
			        p.id AS parcel_id,
			        p.geom AS parcel_geom,
			        p.latitude AS parcel_lat,
			        p.longitude AS parcel_lng,
			        c.id AS complex_id,
			        c.name AS complex_name,
			        c.unit_cnt,
			        c.use_date,
			        coordinate_case.status AS coordinate_case_status,
			        coordinate_case.relation_type,
			        coordinate_case.relation_confidence,
			        COALESCE(display_coordinate.latitude, p.latitude) AS lat,
			        COALESCE(display_coordinate.longitude, p.longitude) AS lng,
			        display_coordinate.coordinate_source,
			        display_coordinate.confidence
			    FROM bounded_parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    LEFT JOIN complex_display_coordinate display_coordinate
			      ON display_coordinate.complex_id = c.id
			     AND (
			         display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
			         OR display_coordinate.confidence >= :trustedBuildingCoordinateConfidence
			    )
			    LEFT JOIN complex_coordinate_case coordinate_case
			      ON coordinate_case.parcel_id = p.id
			    GROUP BY
			        p.id,
			        p.geom,
			        p.latitude,
			        p.longitude,
			        c.id,
			        c.name,
			        c.unit_cnt,
			        c.use_date,
			        coordinate_case.status,
			        coordinate_case.relation_type,
			        coordinate_case.relation_confidence,
			        display_coordinate.latitude,
			        display_coordinate.longitude,
			        display_coordinate.coordinate_source,
			        display_coordinate.confidence
			),
			parcel_flags AS (
			    SELECT
			        parcel_id,
			        count(*) AS complex_count,
			        count(*) FILTER (
			            WHERE coordinate_source = 'BUILDING_FOOTPRINT'
			        ) AS trusted_building_coordinate_count,
			        COALESCE(bool_or(
			            coordinate_case_status = 'RESOLVED'
			            AND relation_type = 'CONCURRENT'
			            AND relation_confidence = 'HIGH'
			        ), false) AS is_concurrent,
			        COALESCE(bool_or(
			            relation_type = 'REDEVELOPED'
			            AND relation_confidence = 'HIGH'
			        ), false) AS is_redeveloped,
			        COALESCE(bool_or(
			            relation_type = 'REDEVELOPED'
			        ), false) AS is_redevelopment_candidate
			    FROM complex_base
			    GROUP BY parcel_id
			),
			redevelopment_generation_base AS (
			    SELECT
			        base.parcel_id,
			        base.complex_id,
			        base.use_date,
			        latest_generation_trade.deal_date AS latest_generation_deal_date,
			        first_generation_trade.deal_date AS first_deal
			    FROM complex_base base
			    JOIN parcel_flags flags
			      ON flags.parcel_id = base.parcel_id
			     AND flags.is_redeveloped
			    LEFT JOIN LATERAL (
			        SELECT trade.deal_date
			        FROM trade
			        WHERE trade.complex_id = base.complex_id
			          AND trade.deleted_at IS NULL
			        ORDER BY trade.deal_date DESC, trade.id DESC
			        LIMIT 1
			    ) latest_generation_trade ON true
			    LEFT JOIN LATERAL (
			        SELECT trade.deal_date
			        FROM trade
			        WHERE trade.complex_id = base.complex_id
			          AND trade.deleted_at IS NULL
			        ORDER BY trade.deal_date ASC, trade.id ASC
			        LIMIT 1
			    ) first_generation_trade ON true
			),
			current_generation AS (
			    SELECT DISTINCT ON (parcel_id)
			        parcel_id,
			        complex_id
			    FROM redevelopment_generation_base
			    ORDER BY
			        parcel_id,
			        use_date DESC NULLS LAST,
			        latest_generation_deal_date DESC NULLS LAST,
			        first_deal DESC NULLS LAST,
			        complex_id DESC
			),
			split_complex_marker AS (
			    SELECT
			        base.parcel_id,
			        base.complex_id,
			        base.complex_name,
			        base.lat,
			        base.lng,
			        COALESCE(base.unit_cnt, 0)::bigint AS unit_cnt_sum,
			        CASE
			            WHEN base.use_date IS NULL THEN NULL
			            ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))
			        END AS building_age,
			        base.parcel_geom
			    FROM complex_base base
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    LEFT JOIN current_generation current_generation
			      ON current_generation.parcel_id = base.parcel_id
			    WHERE (
			        NOT flags.is_redeveloped
			        AND
			        (
			            flags.is_concurrent
			            OR (
			                NOT flags.is_redevelopment_candidate
			                AND flags.trusted_building_coordinate_count > 0
			            )
			        )
			        AND flags.complex_count > 1
			        AND base.coordinate_source = 'BUILDING_FOOTPRINT'
			    )
			       OR (
			           flags.is_redeveloped
			           AND base.complex_id = current_generation.complex_id
			       )
			),
			representative_coordinate AS (
			    SELECT DISTINCT ON (base.parcel_id)
			        base.parcel_id,
			        base.complex_id,
			        base.lat,
			        base.lng
			    FROM complex_base base
			    ORDER BY
			        base.parcel_id,
			        CASE base.coordinate_source
			            WHEN 'BUILDING_FOOTPRINT' THEN 0
			            ELSE 1
			        END,
			        base.confidence DESC NULLS LAST,
			        base.use_date DESC NULLS LAST,
			        base.complex_id DESC
			),
			parcel_marker_base AS (
			    SELECT base.*
			    FROM complex_base base
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    WHERE NOT flags.is_redeveloped
			      AND (
			          NOT (
			              (
			                  flags.is_concurrent
			                  OR (
			                      NOT flags.is_redevelopment_candidate
			                      AND flags.trusted_building_coordinate_count > 0
			                  )
			              )
			              AND flags.complex_count > 1
			          )
			          OR base.coordinate_source IS DISTINCT FROM 'BUILDING_FOOTPRINT'
			      )
			),
			parcel_marker AS (
			    SELECT
			        base.parcel_id,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.complex_id
			            ELSE NULL
			        END AS complex_id,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.lat
			            ELSE MAX(base.parcel_lat)
			        END AS lat,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.lng
			            ELSE MAX(base.parcel_lng)
			        END AS lng,
			        COALESCE(SUM(base.unit_cnt), 0)::bigint AS unit_cnt_sum,
			        MAX(
			            CASE
			                WHEN base.use_date IS NULL THEN NULL
			                ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))
			            END
			        ) AS building_age,
			        base.parcel_geom
			    FROM parcel_marker_base base
			    JOIN representative_coordinate
			      ON representative_coordinate.parcel_id = base.parcel_id
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    GROUP BY
			        base.parcel_id,
			        flags.complex_count,
			        representative_coordinate.complex_id,
			        representative_coordinate.lat,
			        representative_coordinate.lng,
			        base.parcel_geom
			),
			marker_candidates AS (
			    SELECT
			        split_complex_marker.parcel_id,
			        split_complex_marker.complex_id,
			        split_complex_marker.complex_name,
			        split_complex_marker.lat,
			        split_complex_marker.lng,
			        split_complex_marker.unit_cnt_sum,
			        split_complex_marker.building_age,
			        split_complex_marker.parcel_geom
			    FROM split_complex_marker
			    UNION ALL
			    SELECT
			        parcel_marker.parcel_id,
			        parcel_marker.complex_id,
			        NULL AS complex_name,
			        parcel_marker.lat,
			        parcel_marker.lng,
			        parcel_marker.unit_cnt_sum,
			        parcel_marker.building_age,
			        parcel_marker.parcel_geom
			    FROM parcel_marker
			),
			filtered_markers AS (
			    SELECT *
			    FROM marker_candidates
			    WHERE lat BETWEEN :swLat AND :neLat
			      AND lng BETWEEN :swLng AND :neLng
			      AND (CAST(:unitMin AS BIGINT) IS NULL OR unit_cnt_sum >= :unitMin)
			      AND (CAST(:unitMax AS BIGINT) IS NULL OR unit_cnt_sum <= :unitMax)
			      AND (CAST(:ageMin AS INTEGER) IS NULL OR building_age >= :ageMin)
			      AND (CAST(:ageMax AS INTEGER) IS NULL OR building_age <= :ageMax)
			),
			markers_with_trade AS (
			    SELECT
			        filtered_markers.parcel_id,
			        filtered_markers.complex_id,
			        COALESCE(filtered_markers.complex_name, latest_marker_trade.complex_name) AS complex_name,
			        filtered_markers.lat,
			        filtered_markers.lng,
			        latest_marker_trade.deal_amount AS latest_deal_amount,
			        latest_marker_trade.excl_area,
			        filtered_markers.unit_cnt_sum
			    FROM filtered_markers
			    LEFT JOIN LATERAL (
			        SELECT
			            trade.deal_amount,
			            trade.excl_area,
			            complex.name AS complex_name
			        FROM trade
			        JOIN complex ON complex.id = trade.complex_id
			        WHERE filtered_markers.complex_id IS NOT NULL
			          AND trade.complex_id = filtered_markers.complex_id
			          AND trade.deleted_at IS NULL
			        ORDER BY trade.deal_date DESC, trade.id DESC
			        LIMIT 1
			    ) latest_marker_trade ON true
			    WHERE filtered_markers.complex_id IS NOT NULL
			    UNION ALL
			    SELECT
			        filtered_markers.parcel_id,
			        filtered_markers.complex_id,
			        COALESCE(filtered_markers.complex_name, latest_marker_trade.complex_name) AS complex_name,
			        filtered_markers.lat,
			        filtered_markers.lng,
			        latest_marker_trade.deal_amount AS latest_deal_amount,
			        latest_marker_trade.excl_area,
			        filtered_markers.unit_cnt_sum
			    FROM filtered_markers
			    LEFT JOIN LATERAL (
			        SELECT
			            trade.deal_amount,
			            trade.excl_area,
			            complex.name AS complex_name
			        FROM parcel_marker_base marker_base
			        JOIN trade
			          ON trade.complex_id = marker_base.complex_id
			         AND trade.deleted_at IS NULL
			        JOIN complex ON complex.id = marker_base.complex_id
			        WHERE marker_base.parcel_id = filtered_markers.parcel_id
			        ORDER BY trade.deal_date DESC, trade.id DESC
			        LIMIT 1
			    ) latest_marker_trade ON true
			    WHERE filtered_markers.complex_id IS NULL
			)
			SELECT
			    markers_with_trade.parcel_id,
			    markers_with_trade.complex_id,
			    markers_with_trade.complex_name,
			    markers_with_trade.lat,
			    markers_with_trade.lng,
			    markers_with_trade.latest_deal_amount,
			    markers_with_trade.unit_cnt_sum
			FROM markers_with_trade
			WHERE (CAST(:priceMin AS NUMERIC) IS NULL OR markers_with_trade.latest_deal_amount >= :priceMin)
			  AND (CAST(:priceMax AS NUMERIC) IS NULL OR markers_with_trade.latest_deal_amount <= :priceMax)
			  AND (CAST(:areaMin AS NUMERIC) IS NULL OR markers_with_trade.excl_area >= :areaMin)
			  AND (CAST(:areaMax AS NUMERIC) IS NULL OR markers_with_trade.excl_area <= :areaMax)
			ORDER BY markers_with_trade.parcel_id, markers_with_trade.complex_id
			""")
			.param("swLat", query.swLat())
			.param("swLng", query.swLng())
			.param("neLat", query.neLat())
			.param("neLng", query.neLng())
			.param("trustedBuildingCoordinateConfidence", TRUSTED_BUILDING_COORDINATE_CONFIDENCE)
			.param("unitMin", query.unitMin())
			.param("unitMax", query.unitMax())
			.param("priceMin", eokToTradeAmount(query.priceEokMin()))
			.param("priceMax", eokToTradeAmount(query.priceEokMax()))
			.param("areaMin", pyeongToSquareMeters(query.pyeongMin()))
			.param("areaMax", pyeongToSquareMeters(query.pyeongMax()))
			.param("ageMin", query.ageMin())
			.param("ageMax", query.ageMax())
			.query(this::mapMarker)
			.list();
	}

	private List<ComplexMarkerResult> findComplexMarkersWithTradeFirst(ComplexMarkerQuery query) {
		return jdbcClient.sql("""
			WITH requested_bounds AS (
			    SELECT ST_MakeEnvelope(
			        CAST(:swLng AS DOUBLE PRECISION),
			        CAST(:swLat AS DOUBLE PRECISION),
			        CAST(:neLng AS DOUBLE PRECISION),
			        CAST(:neLat AS DOUBLE PRECISION),
			        4326
			    ) AS geom
			),
			bounded_parcel AS (
			    SELECT p.*
			    FROM parcel p
			    CROSS JOIN requested_bounds bounds
			    WHERE (
			        p.geom IS NOT NULL
			        AND ST_Intersects(p.geom, bounds.geom)
			    )
			       OR (
			           p.geom IS NULL
			           AND p.latitude BETWEEN :swLat AND :neLat
			           AND p.longitude BETWEEN :swLng AND :neLng
			       )
			),
			complex_base AS (
			    SELECT
			        p.id AS parcel_id,
			        p.geom AS parcel_geom,
			        p.latitude AS parcel_lat,
			        p.longitude AS parcel_lng,
			        c.id AS complex_id,
			        c.name AS complex_name,
			        c.unit_cnt,
			        c.use_date,
			        coordinate_case.status AS coordinate_case_status,
			        coordinate_case.relation_type,
			        coordinate_case.relation_confidence,
			        COALESCE(display_coordinate.latitude, p.latitude) AS lat,
			        COALESCE(display_coordinate.longitude, p.longitude) AS lng,
			        display_coordinate.coordinate_source,
			        display_coordinate.confidence,
			        latest_complex_trade.deal_date AS latest_complex_deal_date,
			        latest_complex_trade.deal_amount AS latest_complex_deal_amount,
			        latest_complex_trade.excl_area AS latest_complex_excl_area,
			        first_complex_trade.deal_date AS first_deal
			    FROM bounded_parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    LEFT JOIN complex_display_coordinate display_coordinate
			      ON display_coordinate.complex_id = c.id
			     AND (
			         display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
			         OR display_coordinate.confidence >= :trustedBuildingCoordinateConfidence
			     )
			    LEFT JOIN complex_coordinate_case coordinate_case
			      ON coordinate_case.parcel_id = p.id
			    LEFT JOIN LATERAL (
			        SELECT
			            trade.deal_date,
			            trade.deal_amount,
			            trade.excl_area
			        FROM trade
			        WHERE trade.complex_id = c.id
			          AND trade.deleted_at IS NULL
			        ORDER BY trade.deal_date DESC, trade.id DESC
			        LIMIT 1
			    ) latest_complex_trade ON true
			    LEFT JOIN LATERAL (
			        SELECT trade.deal_date
			        FROM trade
			        WHERE trade.complex_id = c.id
			          AND trade.deleted_at IS NULL
			          AND EXISTS (
			              SELECT 1
			              FROM complex_coordinate_case first_trade_case
			              WHERE first_trade_case.parcel_id = p.id
			                AND first_trade_case.relation_type = 'REDEVELOPED'
			                AND first_trade_case.relation_confidence = 'HIGH'
			          )
			        ORDER BY trade.deal_date ASC, trade.id ASC
			        LIMIT 1
			    ) first_complex_trade ON true
			    GROUP BY
			        p.id,
			        p.geom,
			        p.latitude,
			        p.longitude,
			        c.id,
			        c.name,
			        c.unit_cnt,
			        c.use_date,
			        coordinate_case.status,
			        coordinate_case.relation_type,
			        coordinate_case.relation_confidence,
			        display_coordinate.latitude,
			        display_coordinate.longitude,
			        display_coordinate.coordinate_source,
			        display_coordinate.confidence,
			        latest_complex_trade.deal_date,
			        latest_complex_trade.deal_amount,
			        latest_complex_trade.excl_area,
			        first_complex_trade.deal_date
			),
			parcel_flags AS (
			    SELECT
			        parcel_id,
			        count(*) AS complex_count,
			        count(*) FILTER (
			            WHERE coordinate_source = 'BUILDING_FOOTPRINT'
			        ) AS trusted_building_coordinate_count,
			        COALESCE(bool_or(
			            coordinate_case_status = 'RESOLVED'
			            AND relation_type = 'CONCURRENT'
			            AND relation_confidence = 'HIGH'
			        ), false) AS is_concurrent,
			        COALESCE(bool_or(
			            relation_type = 'REDEVELOPED'
			            AND relation_confidence = 'HIGH'
			        ), false) AS is_redeveloped,
			        COALESCE(bool_or(
			            relation_type = 'REDEVELOPED'
			        ), false) AS is_redevelopment_candidate
			    FROM complex_base
			    GROUP BY parcel_id
			),
			current_generation AS (
			    SELECT DISTINCT ON (parcel_id)
			        parcel_id,
			        complex_id
			    FROM complex_base
			    ORDER BY
			        parcel_id,
			        use_date DESC NULLS LAST,
			        latest_complex_deal_date DESC NULLS LAST,
			        first_deal DESC NULLS LAST,
			        complex_id DESC
			),
			split_complex_marker AS (
			    SELECT
			        base.parcel_id,
			        base.complex_id,
			        base.complex_name,
			        base.lat,
			        base.lng,
			        base.latest_complex_deal_amount AS latest_deal_amount,
			        base.latest_complex_excl_area AS excl_area,
			        COALESCE(base.unit_cnt, 0)::bigint AS unit_cnt_sum,
			        CASE
			            WHEN base.use_date IS NULL THEN NULL
			            ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))
			        END AS building_age,
			        base.parcel_geom
			    FROM complex_base base
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    LEFT JOIN current_generation current_generation
			      ON current_generation.parcel_id = base.parcel_id
			    WHERE (
			        NOT flags.is_redeveloped
			        AND
			        (
			            flags.is_concurrent
			            OR (
			                NOT flags.is_redevelopment_candidate
			                AND flags.trusted_building_coordinate_count > 0
			            )
			        )
			        AND flags.complex_count > 1
			        AND base.coordinate_source = 'BUILDING_FOOTPRINT'
			    )
			       OR (
			           flags.is_redeveloped
			           AND base.complex_id = current_generation.complex_id
			       )
			),
			representative_coordinate AS (
			    SELECT DISTINCT ON (base.parcel_id)
			        base.parcel_id,
			        base.complex_id,
			        base.lat,
			        base.lng
			    FROM complex_base base
			    ORDER BY
			        base.parcel_id,
			        CASE base.coordinate_source
			            WHEN 'BUILDING_FOOTPRINT' THEN 0
			            ELSE 1
			        END,
			        base.confidence DESC NULLS LAST,
			        base.latest_complex_deal_date DESC NULLS LAST,
			        base.complex_id DESC
			),
			parcel_marker_base AS (
			    SELECT base.*
			    FROM complex_base base
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    WHERE NOT flags.is_redeveloped
			      AND (
			          NOT (
			              (
			                  flags.is_concurrent
			                  OR (
			                      NOT flags.is_redevelopment_candidate
			                      AND flags.trusted_building_coordinate_count > 0
			                  )
			              )
			              AND flags.complex_count > 1
			          )
			          OR base.coordinate_source IS DISTINCT FROM 'BUILDING_FOOTPRINT'
			      )
			),
			parcel_marker AS (
			    SELECT
			        base.parcel_id,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.complex_id
			            ELSE NULL
			        END AS complex_id,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.lat
			            ELSE MAX(base.parcel_lat)
			        END AS lat,
			        CASE
			            WHEN flags.complex_count = 1 THEN representative_coordinate.lng
			            ELSE MAX(base.parcel_lng)
			        END AS lng,
			        COALESCE(SUM(base.unit_cnt), 0)::bigint AS unit_cnt_sum,
			        MAX(
			            CASE
			                WHEN base.use_date IS NULL THEN NULL
			                ELSE EXTRACT(YEAR FROM age(CURRENT_DATE, base.use_date))
			            END
			        ) AS building_age
			    FROM parcel_marker_base base
			    JOIN representative_coordinate
			      ON representative_coordinate.parcel_id = base.parcel_id
			    JOIN parcel_flags flags ON flags.parcel_id = base.parcel_id
			    GROUP BY
			        base.parcel_id,
			        flags.complex_count,
			        representative_coordinate.complex_id,
			        representative_coordinate.lat,
			        representative_coordinate.lng
			),
			latest_parcel_trade AS (
			    SELECT DISTINCT ON (base.parcel_id)
			        base.parcel_id,
			        base.latest_complex_deal_amount AS latest_deal_amount,
			        base.latest_complex_excl_area AS excl_area,
			        base.complex_name
			    FROM complex_base base
			    JOIN parcel_marker_base marker_base
			      ON marker_base.parcel_id = base.parcel_id
			     AND marker_base.complex_id = base.complex_id
			    WHERE base.latest_complex_deal_date IS NOT NULL
			    ORDER BY base.parcel_id, base.latest_complex_deal_date DESC, base.complex_id DESC
			),
			markers AS (
			    SELECT
			        split_complex_marker.parcel_id,
			        split_complex_marker.complex_id,
			        split_complex_marker.complex_name,
			        split_complex_marker.lat,
			        split_complex_marker.lng,
			        split_complex_marker.latest_deal_amount,
			        split_complex_marker.excl_area,
			        split_complex_marker.unit_cnt_sum,
			        split_complex_marker.building_age,
			        split_complex_marker.parcel_geom
			    FROM split_complex_marker
			    UNION ALL
			    SELECT
			        parcel_marker.parcel_id,
			        parcel_marker.complex_id,
			        latest_parcel_trade.complex_name,
			        parcel_marker.lat,
			        parcel_marker.lng,
			        latest_parcel_trade.latest_deal_amount,
			        latest_parcel_trade.excl_area,
			        parcel_marker.unit_cnt_sum,
			        parcel_marker.building_age,
			        base.parcel_geom
			    FROM parcel_marker
			    JOIN complex_base base ON base.parcel_id = parcel_marker.parcel_id
			    LEFT JOIN latest_parcel_trade ON latest_parcel_trade.parcel_id = parcel_marker.parcel_id
			    GROUP BY
			        parcel_marker.parcel_id,
			        parcel_marker.complex_id,
			        parcel_marker.lat,
			        parcel_marker.lng,
			        latest_parcel_trade.complex_name,
			        latest_parcel_trade.latest_deal_amount,
			        latest_parcel_trade.excl_area,
			        parcel_marker.unit_cnt_sum,
			        parcel_marker.building_age,
			        base.parcel_geom
			)
			SELECT
			    markers.parcel_id,
			    markers.complex_id,
			    markers.complex_name,
			    markers.lat,
			    markers.lng,
			    markers.latest_deal_amount,
			    markers.unit_cnt_sum
			FROM markers
			WHERE markers.lat BETWEEN :swLat AND :neLat
			  AND markers.lng BETWEEN :swLng AND :neLng
			  AND (CAST(:priceMin AS NUMERIC) IS NULL OR markers.latest_deal_amount >= :priceMin)
			  AND (CAST(:priceMax AS NUMERIC) IS NULL OR markers.latest_deal_amount <= :priceMax)
			  AND (CAST(:areaMin AS NUMERIC) IS NULL OR markers.excl_area >= :areaMin)
			  AND (CAST(:areaMax AS NUMERIC) IS NULL OR markers.excl_area <= :areaMax)
			ORDER BY markers.parcel_id, markers.complex_id
			""")
			.param("swLat", query.swLat())
			.param("swLng", query.swLng())
			.param("neLat", query.neLat())
			.param("neLng", query.neLng())
			.param("trustedBuildingCoordinateConfidence", TRUSTED_BUILDING_COORDINATE_CONFIDENCE)
			.param("priceMin", eokToTradeAmount(query.priceEokMin()))
			.param("priceMax", eokToTradeAmount(query.priceEokMax()))
			.param("areaMin", pyeongToSquareMeters(query.pyeongMin()))
			.param("areaMax", pyeongToSquareMeters(query.pyeongMax()))
			.query(this::mapMarker)
			.list();
	}

	private boolean hasMarkerShapeFilter(ComplexMarkerQuery query) {
		return query.unitMin() != null
			|| query.unitMax() != null
			|| query.ageMin() != null
			|| query.ageMax() != null;
	}

	private ComplexMarkerResult mapMarker(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ComplexMarkerResult(
			resultSet.getLong("parcel_id"),
			longOrNull(resultSet, "complex_id"),
			resultSet.getString("complex_name"),
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
