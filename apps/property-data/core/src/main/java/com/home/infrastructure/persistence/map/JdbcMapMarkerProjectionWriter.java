package com.home.infrastructure.persistence.map;

import com.home.application.map.MapMarkerProjectionGeneration;
import com.home.application.map.MapMarkerProjectionRepository;
import com.home.domain.coordinate.CoordinateDisplayPolicy;
import com.home.domain.coordinate.CoordinateSource;
import com.home.domain.map.MapMarkerGenerationStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcMapMarkerProjectionWriter implements MapMarkerProjectionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcMapMarkerProjectionWriter.class);
    private static final String BUILD_COMPLEX_MARKERS_SQL = loadBuildSql();
    private static final String PROJECTION_STATEMENT_TIMEOUT_MILLIS = "3600000";

    private final JdbcClient jdbcClient;
    private final TransactionOperations transactions;

    @Autowired
    public JdbcMapMarkerProjectionWriter(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        this(jdbcClient, repeatableReadTransactions(transactionManager));
    }

    JdbcMapMarkerProjectionWriter(JdbcClient jdbcClient, TransactionOperations transactions) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public MapMarkerProjectionGeneration rebuildAndActivate(String sourceWatermark) {
        String normalizedWatermark = requireWatermark(sourceWatermark);
        long generationId =
                Objects.requireNonNull(transactions.execute(status -> createGeneration(normalizedWatermark)));
        try {
            ProjectionEvidence evidence =
                    Objects.requireNonNull(transactions.execute(status -> projectAndValidate(generationId)));
            transactions.executeWithoutResult(status -> activate(generationId));
            MapMarkerProjectionGeneration generation = new MapMarkerProjectionGeneration(
                    generationId,
                    normalizedWatermark,
                    evidence.complexMarkerCount(),
                    evidence.regionMarkerCount(),
                    evidence.markerHash());
            removeExpiredRetiredGenerationsBestEffort();
            return generation;
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> markFailed(generationId, exception));
            throw exception;
        }
    }

    private void removeExpiredRetiredGenerationsBestEffort() {
        try {
            transactions.executeWithoutResult(status -> removeExpiredRetiredGenerations());
        } catch (RuntimeException exception) {
            log.warn(
                    "Map marker retired generation cleanup failed type={}",
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public long activeGenerationId() {
        return jdbcClient
                .sql("SELECT generation_id FROM map_marker_active_generation WHERE singleton_id = 1")
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Active map marker generation is missing"));
    }

    private long createGeneration(String sourceWatermark) {
        return jdbcClient
                .sql("""
				INSERT INTO map_marker_generation (status, source_watermark)
				VALUES (:status, :sourceWatermark)
				RETURNING id
				""")
                .param("status", MapMarkerGenerationStatus.BUILDING.storedValue())
                .param("sourceWatermark", sourceWatermark)
                .query(Long.class)
                .single();
    }

    private ProjectionEvidence projectAndValidate(long generationId) {
        jdbcClient
                .sql("SELECT set_config('statement_timeout', :timeoutMillis, true)")
                .param("timeoutMillis", PROJECTION_STATEMENT_TIMEOUT_MILLIS)
                .query(String.class)
                .single();
        jdbcClient
                .sql(BUILD_COMPLEX_MARKERS_SQL)
                .param("generationId", generationId)
                .param(
                        "trustedBuildingCoordinateConfidence",
                        CoordinateDisplayPolicy.TRUSTED_BUILDING_FOOTPRINT_CONFIDENCE)
                .param("buildingFootprintSource", CoordinateSource.BUILDING_FOOTPRINT.storedValue())
                .update();
        jdbcClient.sql("""
				INSERT INTO map_region_marker_projection (
				    generation_id,
				    region_id,
				    region_type,
				    region_name,
				    lat,
				    lng,
				    point,
				    unit_cnt_sum
				)
				SELECT
				    :generationId,
				    id,
				    region_type,
				    name,
				    center_lat,
				    center_lng,
				    ST_SetSRID(ST_MakePoint(center_lng, center_lat), 4326),
				    unit_cnt_sum
				FROM region
				WHERE unit_cnt_sum IS NOT NULL
				  AND center_lat BETWEEN 33 AND 39
				  AND center_lng BETWEEN 124 AND 132
				ORDER BY id
				""").param("generationId", generationId).update();

        ProjectionEvidence evidence = jdbcClient
                .sql("""
				WITH canonical_marker AS (
				    SELECT
				        parcel_id || '|' || COALESCE(complex_id::text, '') || '|'
				        || COALESCE(complex_name, '') || '|'
				        || lat || '|' || lng || '|' || COALESCE(latest_deal_amount::text, '') || '|'
				        || unit_cnt_sum AS canonical_value
				    FROM map_complex_marker_projection
				    WHERE generation_id = :generationId
				),
				counts AS (
				    SELECT
				        (SELECT count(*) FROM map_complex_marker_projection
				         WHERE generation_id = :generationId) AS complex_marker_count,
				        (SELECT count(*) FROM map_region_marker_projection
				         WHERE generation_id = :generationId) AS region_marker_count
				)
				SELECT
				    counts.complex_marker_count,
				    counts.region_marker_count,
				    encode(digest(COALESCE(string_agg(
				        canonical_marker.canonical_value,
				        E'\\n' ORDER BY canonical_marker.canonical_value
				    ), ''), 'sha256'), 'hex') AS marker_hash
				FROM counts
				LEFT JOIN canonical_marker ON true
				GROUP BY counts.complex_marker_count, counts.region_marker_count
				""")
                .param("generationId", generationId)
                .query((resultSet, rowNumber) -> new ProjectionEvidence(
                        resultSet.getLong("complex_marker_count"),
                        resultSet.getLong("region_marker_count"),
                        resultSet.getString("marker_hash")))
                .single();

        int updated = jdbcClient
                .sql("""
				UPDATE map_marker_generation
				SET status = :validatedStatus,
				    complex_marker_count = :complexMarkerCount,
				    region_marker_count = :regionMarkerCount,
				    marker_hash = :markerHash,
				    validated_at = now()
				WHERE id = :generationId
				  AND status = :buildingStatus
				""")
                .param("validatedStatus", MapMarkerGenerationStatus.VALIDATED.storedValue())
                .param("complexMarkerCount", evidence.complexMarkerCount())
                .param("regionMarkerCount", evidence.regionMarkerCount())
                .param("markerHash", evidence.markerHash())
                .param("generationId", generationId)
                .param("buildingStatus", MapMarkerGenerationStatus.BUILDING.storedValue())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Map marker generation validation transition was rejected");
        }
        return evidence;
    }

    private void activate(long generationId) {
        jdbcClient.sql("""
				INSERT INTO map_marker_active_generation (singleton_id, generation_id)
				VALUES (1, :generationId)
				ON CONFLICT (singleton_id) DO UPDATE
				SET generation_id = EXCLUDED.generation_id,
				    activated_at = now()
				""").param("generationId", generationId).update();
    }

    private void removeExpiredRetiredGenerations() {
        jdbcClient.sql("""
			DELETE FROM map_marker_generation generation
			WHERE generation.status = 'RETIRED'
			  AND generation.id NOT IN (
			      SELECT retained.id
			      FROM map_marker_generation retained
			      WHERE retained.status = 'RETIRED'
			      ORDER BY retained.activated_at DESC, retained.id DESC
			      LIMIT 1
			  )
			""").update();
    }

    private void markFailed(long generationId, RuntimeException exception) {
        String failureReason = exception.getClass().getSimpleName();
        jdbcClient
                .sql("""
				UPDATE map_marker_generation
				SET status = :failedStatus,
				    completed_at = now(),
				    failure_reason = :failureReason
				WHERE id = :generationId
				  AND status IN (:buildingStatus, :validatedStatus)
				""")
                .param("failedStatus", MapMarkerGenerationStatus.FAILED.storedValue())
                .param("failureReason", failureReason)
                .param("generationId", generationId)
                .param("buildingStatus", MapMarkerGenerationStatus.BUILDING.storedValue())
                .param("validatedStatus", MapMarkerGenerationStatus.VALIDATED.storedValue())
                .update();
    }

    private String requireWatermark(String sourceWatermark) {
        if (sourceWatermark == null || sourceWatermark.isBlank()) {
            throw new IllegalArgumentException("Map marker source watermark must not be blank");
        }
        String normalized = sourceWatermark.trim();
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("Map marker source watermark must be at most 256 characters");
        }
        return normalized;
    }

    private static String loadBuildSql() {
        try (InputStream input =
                JdbcMapMarkerProjectionWriter.class.getResourceAsStream("map-marker-projection-build.sql")) {
            if (input == null) {
                throw new IllegalStateException("Map marker projection build SQL is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load map marker projection build SQL", exception);
        }
    }

    private static TransactionTemplate repeatableReadTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private record ProjectionEvidence(long complexMarkerCount, long regionMarkerCount, String markerHash) {}
}
