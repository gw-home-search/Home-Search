package com.home.sourcedata.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

final class LegacyCoordinateSourceFingerprint {
    private static final Set<String> EXPECTED_TABLES = Set.of(
        "coordinate_snapshot_run", "parcel_coordinate_snapshot");
    private static final Set<String> EXPECTED_COLUMNS = Set.of(
        "coordinate_snapshot_run|id|bigint|int8|NO|YES||64|0",
        "coordinate_snapshot_run|snapshot_version|character varying|varchar|NO|NO|64||",
        "coordinate_snapshot_run|source_dataset|character varying|varchar|NO|NO|64||",
        "coordinate_snapshot_run|source_dir|text|text|YES|NO|||",
        "coordinate_snapshot_run|source_srid|integer|int4|NO|NO||32|0",
        "coordinate_snapshot_run|target_srid|integer|int4|NO|NO||32|0",
        "coordinate_snapshot_run|status|character varying|varchar|NO|NO|32||",
        "coordinate_snapshot_run|file_count|integer|int4|NO|NO||32|0",
        "coordinate_snapshot_run|region_count|integer|int4|NO|NO||32|0",
        "coordinate_snapshot_run|raw_feature_count|bigint|int8|NO|NO||64|0",
        "coordinate_snapshot_run|pnu_count|bigint|int8|NO|NO||64|0",
        "coordinate_snapshot_run|invalid_count|bigint|int8|NO|NO||64|0",
        "coordinate_snapshot_run|duplicate_pnu_count|bigint|int8|NO|NO||64|0",
        "coordinate_snapshot_run|synced_parcel_count|bigint|int8|NO|NO||64|0",
        "coordinate_snapshot_run|report_json|jsonb|jsonb|NO|NO|||",
        "coordinate_snapshot_run|failure_reason|text|text|YES|NO|||",
        "coordinate_snapshot_run|started_at|timestamp with time zone|timestamptz|NO|NO|||",
        "coordinate_snapshot_run|finished_at|timestamp with time zone|timestamptz|YES|NO|||",
        "parcel_coordinate_snapshot|pnu|character varying|varchar|NO|NO|19||",
        "parcel_coordinate_snapshot|region_code|character varying|varchar|NO|NO|8||",
        "parcel_coordinate_snapshot|latitude|numeric|numeric|NO|NO||10|7",
        "parcel_coordinate_snapshot|longitude|numeric|numeric|NO|NO||10|7",
        "parcel_coordinate_snapshot|point|USER-DEFINED|geometry|NO|NO|||",
        "parcel_coordinate_snapshot|geom|USER-DEFINED|geometry|NO|NO|||",
        "parcel_coordinate_snapshot|snapshot_version|character varying|varchar|NO|NO|64||",
        "parcel_coordinate_snapshot|source_file|text|text|NO|NO|||",
        "parcel_coordinate_snapshot|run_id|bigint|int8|YES|NO||64|0",
        "parcel_coordinate_snapshot|created_at|timestamp with time zone|timestamptz|NO|NO|||",
        "parcel_coordinate_snapshot|updated_at|timestamp with time zone|timestamptz|NO|NO|||");
    private static final Set<String> EXPECTED_CONSTRAINTS = Set.of(
        "coordinate_snapshot_run_duplicate_pnu_count_check", "coordinate_snapshot_run_file_count_check",
        "coordinate_snapshot_run_invalid_count_check", "coordinate_snapshot_run_pkey",
        "coordinate_snapshot_run_pnu_count_check", "coordinate_snapshot_run_raw_feature_count_check",
        "coordinate_snapshot_run_region_count_check", "coordinate_snapshot_run_status_check",
        "coordinate_snapshot_run_synced_parcel_count_check", "ck_parcel_coordinate_snapshot_geom_srid",
        "ck_parcel_coordinate_snapshot_geom_valid", "ck_parcel_coordinate_snapshot_point_srid",
        "parcel_coordinate_snapshot_latitude_check", "parcel_coordinate_snapshot_longitude_check",
        "parcel_coordinate_snapshot_pkey", "parcel_coordinate_snapshot_pnu_check",
        "parcel_coordinate_snapshot_run_id_fkey");
    private static final Set<String> EXPECTED_INDEXES = Set.of(
        "coordinate_snapshot_run_pkey", "ix_coordinate_snapshot_run_status_started",
        "ix_parcel_coordinate_snapshot_geom", "ix_parcel_coordinate_snapshot_point",
        "ix_parcel_coordinate_snapshot_region_code", "parcel_coordinate_snapshot_pkey");

    LegacyFingerprintEvidence verify(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!exists(connection, "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname='postgis')")) {
                throw mismatch("PostGIS extension is missing");
            }
            if (exists(connection, "SELECT to_regclass('reference.flyway_schema_history') IS NOT NULL")) {
                throw mismatch("Flyway history already exists");
            }
            if (exists(connection, "SELECT to_regclass('geo_enrichment.vworld_wfs_footprint_cache') IS NOT NULL")) {
                throw mismatch("geo enrichment schema was already applied");
            }
            Set<String> tables = strings(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema='reference' AND table_type='BASE TABLE' ORDER BY table_name");
            Set<String> columns = strings(connection, """
                SELECT concat_ws('|', table_name, column_name, data_type, udt_name, is_nullable, is_identity,
                    COALESCE(character_maximum_length::text,''), COALESCE(numeric_precision::text,''), COALESCE(numeric_scale::text,''))
                FROM information_schema.columns
                WHERE table_schema='reference'
                ORDER BY table_name, ordinal_position
                """);
            Set<String> constraints = strings(connection, """
                SELECT con.conname
                FROM pg_constraint con
                JOIN pg_class c ON c.oid=con.conrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='reference' AND con.convalidated
                ORDER BY con.conname
                """);
            Set<String> indexes = strings(connection, "SELECT indexname FROM pg_indexes WHERE schemaname='reference' ORDER BY indexname");
            assertMatches(new LegacySchemaSnapshot(tables, columns, constraints, indexes));
            return new LegacyFingerprintEvidence(estimates(connection));
        }
    }

    void assertMatches(LegacySchemaSnapshot actual) {
        compare("tables", EXPECTED_TABLES, actual.tables());
        compare("columns", EXPECTED_COLUMNS, actual.columns());
        compare("constraints", EXPECTED_CONSTRAINTS, actual.constraints());
        compare("indexes", EXPECTED_INDEXES, actual.indexes());
    }

    static LegacySchemaSnapshot expectedSnapshot() {
        return new LegacySchemaSnapshot(EXPECTED_TABLES, EXPECTED_COLUMNS, EXPECTED_CONSTRAINTS, EXPECTED_INDEXES);
    }

    private void compare(String kind, Set<String> expected, Set<String> actual) {
        if (!expected.equals(actual)) {
            Set<String> missing = new LinkedHashSet<>(expected); missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual); unexpected.removeAll(expected);
            throw mismatch(kind + " mismatch; missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    private boolean exists(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            result.next(); return result.getBoolean(1);
        }
    }

    private Set<String> strings(Connection connection, String sql) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            while (result.next()) values.add(result.getString(1));
        }
        return Set.copyOf(values);
    }

    private Map<String, Long> estimates(Connection connection) throws SQLException {
        Map<String, Long> values = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT c.relname, c.reltuples::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='reference' AND c.relname IN ('coordinate_snapshot_run','parcel_coordinate_snapshot') ORDER BY c.relname
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) values.put(result.getString(1), result.getLong(2));
        }
        return Map.copyOf(values);
    }

    private LegacyFingerprintMismatchException mismatch(String message) {
        return new LegacyFingerprintMismatchException("legacy coordinate source fingerprint rejected: " + message);
    }

    record LegacySchemaSnapshot(Set<String> tables, Set<String> columns, Set<String> constraints, Set<String> indexes) {
        LegacySchemaSnapshot {
            tables = Set.copyOf(tables); columns = Set.copyOf(columns);
            constraints = Set.copyOf(constraints); indexes = Set.copyOf(indexes);
        }
    }
    record LegacyFingerprintEvidence(Map<String, Long> estimatedRows) {
        LegacyFingerprintEvidence { estimatedRows = Map.copyOf(estimatedRows); }
    }
    static final class LegacyFingerprintMismatchException extends RuntimeException {
        LegacyFingerprintMismatchException(String message) { super(message); }
    }
}
