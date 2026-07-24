package com.home.infrastructure.persistence.ingest;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class JdbcPostgresTestSupport extends JdbcPostgresContainerSupport {

    private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();
    private static final Object MIGRATION_LOCK = new Object();
    private static final List<String> RESET_TABLES = List.of(
            "public.market_insight_trade_item",
            "public.market_insight_snapshot_execution",
            "public.market_insight_snapshot",
            "reference.coordinate_snapshot_publish_checkpoint",
            "reference.coordinate_snapshot_publish_chunk_checkpoint",
            "reference.coordinate_snapshot_region_checkpoint",
            "reference.coordinate_snapshot_stage_chunk_checkpoint",
            "reference.parcel_coordinate_snapshot_publish",
            "reference.parcel_coordinate_snapshot_stage",
            "reference.parcel_coordinate_snapshot",
            "reference.coordinate_snapshot_run",
            "public.trade_source_key_registry",
            "public.trade_match_evidence",
            "public.trade",
            "public.complex_display_coordinate",
            "public.complex_building_link",
            "public.complex_coordinate_case",
            "public.complex_metadata_admin_decision",
            "public.complex_metadata_enrichment_attempt",
            "public.complex_name_alias",
            "public.parcel_coordinate_override",
            "public.complex",
            "public.parcel",
            "public.building_footprint_snapshot",
            "public.odcloud_pnu_prefix_alias",
            "public.rtms_collection_work_unit",
            "public.rtms_ingest_run",
            "public.raw_trade_ingest",
            "public.rtms_collection_execution",
            "public.region");
    private static boolean migrated;

    static {
        POSTGRES.start();
    }

    @BeforeEach
    protected void resetDatabase() {
        initializeJdbc(POSTGRES);
        ensureAiReaderRole();
        ensurePropertyRuntimeRole();
        migrateLatestOnce();
        truncateTables();
    }

    private void migrateLatestOnce() {
        if (migrated) {
            return;
        }
        synchronized (MIGRATION_LOCK) {
            if (migrated) {
                return;
            }
            flyway(null).clean();
            flyway(null).migrate();
            migrated = true;
        }
    }

    private void truncateTables() {
        transactionTemplate.executeWithoutResult(status -> {
            for (String table : extraResetTables()) {
                jdbcClient.sql("DELETE FROM " + table).update();
            }
            for (String table : RESET_TABLES) {
                if (tableExists(table)) {
                    jdbcClient.sql("DELETE FROM " + table).update();
                }
            }
            resetSequences();
        });
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("SELECT to_regclass(:tableName) IS NOT NULL")
                .param("tableName", table)
                .query(Boolean.class)
                .single());
    }

    private List<String> extraResetTables() {
        return jdbcClient.sql("""
			SELECT format('%I.%I', namespace.nspname, relation.relname)
			FROM pg_class relation
			JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
			WHERE namespace.nspname IN ('public', 'reference')
			  AND relation.relkind = 'r'
			  AND relation.relispartition = false
			  AND relation.relname <> 'flyway_schema_history'
			ORDER BY namespace.nspname, relation.relname
			""").query(String.class).list().stream()
                .filter(table -> !RESET_TABLES.contains(table))
                .toList();
    }

    private void resetSequences() {
        List<String> sequences = jdbcClient.sql("""
			SELECT format('%I.%I', sequence_schema, sequence_name)
			FROM information_schema.sequences
			WHERE sequence_schema IN ('public', 'reference')
			ORDER BY sequence_schema, sequence_name
			""").query(String.class).list();
        for (String sequence : sequences) {
            jdbcClient.sql("ALTER SEQUENCE " + sequence + " RESTART WITH 1").update();
        }
    }

    protected long tradeCount() {
        return jdbcClient.sql("SELECT count(*) FROM trade").query(Long.class).single();
    }

    protected long rawCount() {
        return jdbcClient
                .sql("SELECT count(*) FROM raw_trade_ingest")
                .query(Long.class)
                .single();
    }

    protected void seedComplex() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment', 740)
			""").update();
    }

    protected void seedPropertyExplorationData() {
        jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type, center_lat, center_lng)
			VALUES (1, '11', 'Seoul', 'si-do', 37.5663, 126.9780)
			""").update();
        jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473)
			""").update();
        jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (111, 11, '11680103', 'Sample-dong', 'eup-myeon-dong', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 111, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
        jdbcClient.sql("""
			INSERT INTO complex (
			    id,
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    dong_cnt,
			    unit_cnt,
			    plat_area,
			    arch_area,
			    tot_area,
			    bc_rat,
			    vl_rat,
			    use_date
			)
			VALUES (
			    501,
			    1001,
			    'COMPLEX-PK-501',
			    'APT-501',
			    'Sample Apartment',
			    'Sample trade name',
			    8,
			    740,
			    12345.67,
			    2345.67,
			    98765.43,
			    22.50,
			    199.80,
			    DATE '2015-03-20'
			)
			""").update();
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES
			    (90001, 'RTMS', 'sample-rtms-20251201', '11680', '202512', 1, '{}', 'hash-1', 'NORMALIZED', now()),
			    (90002, 'RTMS', 'sample-rtms-20251215', '11680', '202512', 1, '{}', 'hash-2', 'NORMALIZED', now())
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9001, 501, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'sample-rtms-20251201', 'COMPLEX-PK-501', 'APT-501', 90001),
			    (9002, 501, DATE '2025-12-15', 130000, 15, 84.93, '101', 'RTMS', 'sample-rtms-20251215', 'COMPLEX-PK-501', 'APT-501', 90002)
			""").update();
    }
}
