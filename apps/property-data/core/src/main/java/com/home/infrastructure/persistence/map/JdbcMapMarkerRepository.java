package com.home.infrastructure.persistence.map;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMapMarkerRepository implements ComplexMarkerRepository {

    private final JdbcClient jdbcClient;
    private final ComplexMarkerRowMapper rowMapper = new ComplexMarkerRowMapper();

    public JdbcMapMarkerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query) {
        ComplexMarkerJdbcParameters parameters = ComplexMarkerJdbcParameters.from(query);
        return parameters
                .bindProjection(jdbcClient.sql("""
					SELECT
					    marker.parcel_id,
					    marker.complex_id,
					    marker.complex_name,
					    marker.lat,
					    marker.lng,
					    marker.latest_deal_amount,
					    marker.unit_cnt_sum
					FROM map_marker_active_generation active
					JOIN map_complex_marker_projection marker
					  ON marker.generation_id = active.generation_id
					WHERE active.singleton_id = 1
					  AND marker.lat BETWEEN :swLat AND :neLat
					  AND marker.lng BETWEEN :swLng AND :neLng
					  AND (CAST(:priceMin AS NUMERIC) IS NULL OR marker.latest_deal_amount >= :priceMin)
					  AND (CAST(:priceMax AS NUMERIC) IS NULL OR marker.latest_deal_amount <= :priceMax)
					  AND (CAST(:areaMin AS NUMERIC) IS NULL OR marker.latest_excl_area >= :areaMin)
					  AND (CAST(:areaMax AS NUMERIC) IS NULL OR marker.latest_excl_area <= :areaMax)
					  AND (CAST(:unitMin AS BIGINT) IS NULL OR marker.unit_cnt_sum >= :unitMin)
					  AND (CAST(:unitMax AS BIGINT) IS NULL OR marker.unit_cnt_sum <= :unitMax)
					  AND (CAST(:ageMin AS INTEGER) IS NULL OR marker.building_age >= :ageMin)
					  AND (CAST(:ageMax AS INTEGER) IS NULL OR marker.building_age <= :ageMax)
					  AND (
					      (
					          CAST(:bcRatMin AS NUMERIC) IS NULL
					          AND CAST(:bcRatMax AS NUMERIC) IS NULL
					          AND CAST(:vlRatMin AS NUMERIC) IS NULL
					          AND CAST(:vlRatMax AS NUMERIC) IS NULL
					      )
					      OR EXISTS (
					          SELECT 1
					          FROM jsonb_array_elements(marker.ratio_members) ratio
					          WHERE (CAST(:bcRatMin AS NUMERIC) IS NULL
					                    OR (ratio ->> 'bcRat')::numeric >= :bcRatMin)
					            AND (CAST(:bcRatMax AS NUMERIC) IS NULL
					                    OR (ratio ->> 'bcRat')::numeric <= :bcRatMax)
					            AND (CAST(:vlRatMin AS NUMERIC) IS NULL
					                    OR (ratio ->> 'vlRat')::numeric >= :vlRatMin)
					            AND (CAST(:vlRatMax AS NUMERIC) IS NULL
					                    OR (ratio ->> 'vlRat')::numeric <= :vlRatMax)
					      )
					  )
					ORDER BY marker.parcel_id, marker.complex_id
					"""))
                .query(rowMapper::map)
                .list();
    }

    public long activeGenerationId() {
        return jdbcClient
                .sql("SELECT generation_id FROM map_marker_active_generation WHERE singleton_id = 1")
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
