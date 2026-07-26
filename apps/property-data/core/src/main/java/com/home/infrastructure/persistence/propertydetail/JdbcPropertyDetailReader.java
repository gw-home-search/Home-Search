package com.home.infrastructure.persistence.propertydetail;

import com.home.application.propertydetail.ComplexCenter;
import com.home.application.propertydetail.ComplexCenterReader;
import com.home.application.propertydetail.PropertyDetailReader;
import com.home.application.read.BuildingProfileSummaryResult;
import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicQuality;
import com.home.domain.complex.buildingprofile.BuildingProfilePublicScope;
import com.home.domain.complex.buildingprofile.BuildingProfileSeismicDesignStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        return jdbcClient
                .sql("""
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
                .optional()
                .map(this::withBuildingProfile);
    }

    @Override
    public Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
        if (!hasParcel(parcelId)) {
            return Optional.empty();
        }
        List<ComplexSummaryResult> complexes = jdbcClient
                .sql("""
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
        return jdbcClient
                .sql("""
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
                .optional()
                .map(this::withBuildingProfile);
    }

    @Override
    public Optional<ComplexCenter> findComplexCenter(Long complexId) {
        return jdbcClient
                .sql("""
			SELECT
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE c.id = :complexId
			""")
                .param("complexId", complexId)
                .query((resultSet, rowNumber) ->
                        new ComplexCenter(doubleOrNull(resultSet, "latitude"), doubleOrNull(resultSet, "longitude")))
                .optional();
    }

    private boolean hasParcel(Long parcelId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
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
                resultSet.getObject("use_date", LocalDate.class));
    }

    private ParcelDetailResult withBuildingProfile(ParcelDetailResult detail) {
        BuildingProfileSummaryResult profile = jdbcClient
                .sql("""
                    SELECT summary.*
                    FROM complex_building_register_profile_summary summary
                    JOIN building_register_profile_publication publication
                      ON publication.publication_id=summary.publication_id
                     AND publication.status='PUBLISHED'
                    WHERE summary.complex_id=:complexId
                    """)
                .param("complexId", detail.complexId())
                .query(this::mapBuildingProfile)
                .optional()
                .orElse(null);
        return new ParcelDetailResult(
                detail.parcelId(),
                detail.complexId(),
                detail.latitude(),
                detail.longitude(),
                detail.address(),
                detail.displayName(),
                detail.tradeName(),
                detail.name(),
                detail.dongCnt(),
                detail.unitCnt(),
                detail.platArea(),
                detail.archArea(),
                detail.totArea(),
                detail.bcRat(),
                detail.vlRat(),
                detail.useDate(),
                profile);
    }

    private BuildingProfileSummaryResult mapBuildingProfile(ResultSet rs, int rowNumber) throws SQLException {
        BuildingProfileSummaryResult.Ratios ratios = hasAny(
                        rs.getBigDecimal("building_coverage_rate"), rs.getBigDecimal("floor_area_ratio"),
                        rs.getBigDecimal("site_area_m2"), rs.getBigDecimal("building_area_m2"),
                        rs.getBigDecimal("total_floor_area_m2"), rs.getBigDecimal("floor_area_ratio_area_m2"))
                ? new BuildingProfileSummaryResult.Ratios(
                        scope(rs, "ratio_scope"), quality(rs, "ratio_quality"),
                        rs.getBigDecimal("building_coverage_rate"), rs.getBigDecimal("floor_area_ratio"),
                        rs.getBigDecimal("site_area_m2"), rs.getBigDecimal("building_area_m2"),
                        rs.getBigDecimal("total_floor_area_m2"), rs.getBigDecimal("floor_area_ratio_area_m2"))
                : null;
        BuildingProfileSummaryResult.Households households =
                hasAny(longOrNull(rs, "household_count"), longOrNull(rs, "family_count"), longOrNull(rs, "unit_count"))
                        ? new BuildingProfileSummaryResult.Households(
                                scope(rs, "household_scope"),
                                quality(rs, "household_quality"),
                                longOrNull(rs, "household_count"),
                                longOrNull(rs, "family_count"),
                                longOrNull(rs, "unit_count"))
                        : null;
        BuildingProfileSummaryResult.Parking parking = hasAny(
                        longOrNull(rs, "total_parking_count"), rs.getBigDecimal("parking_per_household"),
                        longOrNull(rs, "indoor_mechanical_count"), rs.getBigDecimal("indoor_mechanical_area_m2"),
                        longOrNull(rs, "outdoor_mechanical_count"), rs.getBigDecimal("outdoor_mechanical_area_m2"),
                        longOrNull(rs, "indoor_automatic_count"), rs.getBigDecimal("indoor_automatic_area_m2"),
                        longOrNull(rs, "outdoor_automatic_count"), rs.getBigDecimal("outdoor_automatic_area_m2"))
                ? new BuildingProfileSummaryResult.Parking(
                        scope(rs, "parking_scope"), quality(rs, "parking_quality"),
                        longOrNull(rs, "total_parking_count"), rs.getBigDecimal("parking_per_household"),
                        longOrNull(rs, "indoor_mechanical_count"), rs.getBigDecimal("indoor_mechanical_area_m2"),
                        longOrNull(rs, "outdoor_mechanical_count"), rs.getBigDecimal("outdoor_mechanical_area_m2"),
                        longOrNull(rs, "indoor_automatic_count"), rs.getBigDecimal("indoor_automatic_area_m2"),
                        longOrNull(rs, "outdoor_automatic_count"), rs.getBigDecimal("outdoor_automatic_area_m2"))
                : null;
        BuildingProfileSummaryResult.Building building = hasAny(
                        longOrNull(rs, "main_building_count"),
                        longOrNull(rs, "attached_building_count"),
                        longOrNull(rs, "max_ground_floor_count"),
                        longOrNull(rs, "max_underground_floor_count"),
                        rs.getBigDecimal("max_height_m"),
                        rs.getArray("structure_names"),
                        rs.getArray("roof_names"),
                        rs.getArray("primary_use_names"))
                ? new BuildingProfileSummaryResult.Building(
                        scope(rs, "building_scope"),
                        quality(rs, "building_quality"),
                        longOrNull(rs, "main_building_count"),
                        longOrNull(rs, "attached_building_count"),
                        longOrNull(rs, "max_ground_floor_count"),
                        longOrNull(rs, "max_underground_floor_count"),
                        rs.getBigDecimal("max_height_m"),
                        strings(rs, "structure_names"),
                        strings(rs, "roof_names"),
                        strings(rs, "primary_use_names"))
                : null;
        BuildingProfileSummaryResult.Elevators elevators =
                hasAny(longOrNull(rs, "ride_elevator_count"), longOrNull(rs, "emergency_elevator_count"))
                        ? new BuildingProfileSummaryResult.Elevators(
                                scope(rs, "elevator_scope"), quality(rs, "elevator_quality"),
                                longOrNull(rs, "ride_elevator_count"), longOrNull(rs, "emergency_elevator_count"))
                        : null;
        String seismic = rs.getString("seismic_design_status");
        BuildingProfileSummaryResult.Safety safety = hasAny(seismic, rs.getArray("seismic_abilities"))
                ? new BuildingProfileSummaryResult.Safety(
                        scope(rs, "safety_scope"),
                        quality(rs, "safety_quality"),
                        seismic == null ? null : BuildingProfileSeismicDesignStatus.valueOf(seismic),
                        strings(rs, "seismic_abilities"))
                : null;
        BuildingProfileSummaryResult.Dates dates = hasAny(
                        rs.getObject("permit_date"),
                        rs.getObject("construction_start_date"),
                        rs.getObject("use_approval_date"))
                ? new BuildingProfileSummaryResult.Dates(
                        scope(rs, "date_scope"),
                        quality(rs, "date_quality"),
                        rs.getObject("permit_date", LocalDate.class),
                        rs.getObject("construction_start_date", LocalDate.class),
                        rs.getObject("use_approval_date", LocalDate.class))
                : null;
        BuildingProfileSummaryResult.Address address =
                hasAny(rs.getString("parcel_address"), rs.getString("road_address"))
                        ? new BuildingProfileSummaryResult.Address(
                                scope(rs, "address_scope"), quality(rs, "address_quality"),
                                rs.getString("parcel_address"), rs.getString("road_address"))
                        : null;
        BuildingProfileSummaryResult.Energy energy = hasAny(
                        rs.getArray("energy_efficiency_grades"),
                        rs.getBigDecimal("energy_saving_rate_min"),
                        rs.getBigDecimal("energy_saving_rate_max"),
                        rs.getBigDecimal("energy_epi_min"),
                        rs.getBigDecimal("energy_epi_max"),
                        rs.getArray("green_building_grades"),
                        rs.getBigDecimal("green_cert_score_min"),
                        rs.getBigDecimal("green_cert_score_max"),
                        rs.getArray("intelligent_building_grades"),
                        rs.getBigDecimal("intelligent_cert_score_min"),
                        rs.getBigDecimal("intelligent_cert_score_max"))
                ? new BuildingProfileSummaryResult.Energy(
                        scope(rs, "energy_scope"),
                        quality(rs, "energy_quality"),
                        strings(rs, "energy_efficiency_grades"),
                        rs.getBigDecimal("energy_saving_rate_min"),
                        rs.getBigDecimal("energy_saving_rate_max"),
                        rs.getBigDecimal("energy_epi_min"),
                        rs.getBigDecimal("energy_epi_max"),
                        strings(rs, "green_building_grades"),
                        rs.getBigDecimal("green_cert_score_min"),
                        rs.getBigDecimal("green_cert_score_max"),
                        strings(rs, "intelligent_building_grades"),
                        rs.getBigDecimal("intelligent_cert_score_min"),
                        rs.getBigDecimal("intelligent_cert_score_max"))
                : null;
        return new BuildingProfileSummaryResult(
                ratios, households, parking, building, elevators, safety, dates, address, energy);
    }

    private BuildingProfilePublicScope scope(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : BuildingProfilePublicScope.valueOf(value);
    }

    private BuildingProfilePublicQuality quality(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : BuildingProfilePublicQuality.valueOf(value);
    }

    private List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private boolean hasAny(Object... values) {
        return java.util.Arrays.stream(values).anyMatch(Objects::nonNull);
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
                resultSet.getObject("use_date", LocalDate.class));
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
