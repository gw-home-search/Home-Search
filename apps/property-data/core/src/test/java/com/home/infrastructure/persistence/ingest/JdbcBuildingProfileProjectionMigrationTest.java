package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfileProjectionMigrationTest extends JdbcMigrationTestSupport {
    @Test
    void createsVersionedNormalizedProfileAndArchiveManifestTables() {
        flyway(null).clean();
        flyway(null).migrate();

        List<String> tables = jdbcClient.sql("""
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema='public'
                      AND table_name IN (
                        'building_register_profile_projection_run',
                        'complex_building_register_profile',
                        'complex_building_register_building',
                        'building_register_profile_projected_quality',
                        'building_register_profile_archive_manifest')
                    ORDER BY table_name
                    """).query(String.class).list();

        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "building_register_profile_projection_run",
                        "complex_building_register_profile",
                        "complex_building_register_building",
                        "building_register_profile_projected_quality",
                        "building_register_profile_archive_manifest");
        assertThat(jdbcClient.sql("""
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema='public'
                      AND table_name IN ('complex_building_register_profile','complex_building_register_building')
                      AND column_name IN ('hhld_cnt','plat_area','main_purps_cd','indr_auto_utcnt','use_apr_day')
                    """).query(Integer.class).single()).isEqualTo(5);
        assertThat(hasPrivilege("complex_building_register_profile", "SELECT,INSERT,UPDATE"))
                .isTrue();
        assertThat(hasPrivilege("complex_building_register_profile", "DELETE")).isFalse();
        assertThat(hasPrivilege("building_register_profile_archive_manifest", "SELECT"))
                .isFalse();
        assertThat(hasPrivilege("building_register_profile_archive_manifest", "INSERT,UPDATE,DELETE"))
                .isFalse();
    }

    private boolean hasPrivilege(String table, String privileges) {
        return jdbcClient
                .sql("""
                    SELECT has_table_privilege(
                      'home_search_property_runtime',CAST(:table AS text),CAST(:privileges AS text))
                    """)
                .param("table", "public." + table)
                .param("privileges", privileges)
                .query(Boolean.class)
                .single();
    }
}
