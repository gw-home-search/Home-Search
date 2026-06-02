package com.home.infrastructure.persistence.read;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.home.application.read.PropertyReadRepository;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeResponse;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcPropertyReadRepository implements PropertyReadRepository {

	private final JdbcClient jdbcClient;

	public JdbcPropertyReadRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<SearchComplexResponse> searchComplexes(String query) {
		String pattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
		String normalizedQuery = normalizeName(query);
		String normalizedPattern = normalizedQuery.isBlank() ? null : "%" + normalizedQuery + "%";
		return jdbcClient.sql("""
			SELECT
			    c.id AS complex_id,
			    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
			    p.id AS parcel_id,
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
			    p.address
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE lower(c.name) LIKE :pattern
			   OR lower(COALESCE(c.trade_name, '')) LIKE :pattern
			   OR lower(COALESCE(p.address, '')) LIKE :pattern
			   OR EXISTS (
			       SELECT 1
			       FROM complex_name_alias a
			       WHERE a.complex_id = c.id
			         AND (
			             lower(a.alias_name) LIKE :pattern
			             OR (
			                 CAST(:normalizedPattern AS VARCHAR) IS NOT NULL
			                 AND a.normalized_name LIKE :normalizedPattern
			             )
			         )
			   )
			ORDER BY COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name), c.id
			LIMIT 20
			""")
			.param("pattern", pattern)
			.param("normalizedPattern", normalizedPattern)
			.query(this::mapSearchComplex)
			.list();
	}

	@Override
	public List<RegionSummaryResponse> findRootRegions() {
		return jdbcClient.sql("""
			SELECT id, name
			FROM region
			WHERE parent_id IS NULL
			ORDER BY id
			""")
			.query(this::mapRegionSummary)
			.list();
	}

	@Override
	public Optional<RegionDetailResponse> findRegionDetail(Long regionId) {
		Optional<RegionRow> region = jdbcClient.sql("""
			SELECT id, name, center_lat, center_lng
			FROM region
			WHERE id = :regionId
			""")
			.param("regionId", regionId)
			.query(this::mapRegionRow)
			.optional();
		if (region.isEmpty()) {
			return Optional.empty();
		}
		List<RegionSummaryResponse> children = jdbcClient.sql("""
			SELECT id, name
			FROM region
			WHERE parent_id = :regionId
			ORDER BY id
			""")
			.param("regionId", regionId)
			.query(this::mapRegionSummary)
			.list();
		RegionRow row = region.get();
		return Optional.of(new RegionDetailResponse(
			row.id(),
			row.name(),
			row.latitude(),
			row.longitude(),
			children
		));
	}

	@Override
	public Optional<ParcelDetailResponse> findParcelDetail(Long parcelId, Long complexId) {
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
	public Optional<TradeListResponse> findTradeList(Long parcelId, Long complexId) {
		if (!hasComplexParent(parcelId, complexId)) {
			return Optional.empty();
		}
		List<TradeResponse> trades = jdbcClient.sql("""
			SELECT
			    t.id AS trade_id,
			    t.deal_date,
			    t.excl_area,
			    t.deal_amount,
			    t.apt_dong,
			    t.floor
			FROM trade t
			JOIN complex c ON c.id = t.complex_id
			WHERE c.parcel_id = :parcelId
			  AND (CAST(:complexId AS BIGINT) IS NULL OR c.id = :complexId)
			  AND t.deleted_at IS NULL
			ORDER BY t.deal_date DESC, t.id DESC
			""")
			.param("parcelId", parcelId)
			.param("complexId", complexId)
			.query(this::mapTrade)
			.list();
		return Optional.of(new TradeListResponse(parcelId, complexId, trades));
	}

	private boolean hasComplexParent(Long parcelId, Long complexId) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.id = :parcelId
			      AND (CAST(:complexId AS BIGINT) IS NULL OR c.id = :complexId)
			)
			""")
			.param("parcelId", parcelId)
			.param("complexId", complexId)
			.query(Boolean.class)
			.single());
	}

	private SearchComplexResponse mapSearchComplex(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SearchComplexResponse(
			resultSet.getLong("complex_id"),
			resultSet.getString("complex_name"),
			resultSet.getLong("parcel_id"),
			resultSet.getDouble("latitude"),
			resultSet.getDouble("longitude"),
			resultSet.getString("address")
		);
	}

	private RegionSummaryResponse mapRegionSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RegionSummaryResponse(
			resultSet.getLong("id"),
			resultSet.getString("name")
		);
	}

	private RegionRow mapRegionRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RegionRow(
			resultSet.getLong("id"),
			resultSet.getString("name"),
			doubleOrNull(resultSet, "center_lat"),
			doubleOrNull(resultSet, "center_lng")
		);
	}

	private ParcelDetailResponse mapParcelDetail(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ParcelDetailResponse(
			resultSet.getLong("parcel_id"),
			resultSet.getLong("complex_id"),
			resultSet.getDouble("latitude"),
			resultSet.getDouble("longitude"),
			resultSet.getString("address"),
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

	private TradeResponse mapTrade(ResultSet resultSet, int rowNumber) throws SQLException {
		return new TradeResponse(
			resultSet.getLong("trade_id"),
			resultSet.getObject("deal_date", LocalDate.class),
			resultSet.getBigDecimal("excl_area"),
			resultSet.getLong("deal_amount"),
			resultSet.getString("apt_dong"),
			integerOrNull(resultSet, "floor")
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

	private String normalizeName(String value) {
		String text = value == null ? "" : value.trim();
		return text.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}

	private record RegionRow(
		Long id,
		String name,
		Double latitude,
		Double longitude
	) {
	}
}
