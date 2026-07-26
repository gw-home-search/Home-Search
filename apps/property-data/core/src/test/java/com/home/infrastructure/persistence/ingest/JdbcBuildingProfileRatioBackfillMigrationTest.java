package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfileRatioBackfillMigrationTest extends JdbcMigrationTestSupport {
    @Test
    @DisplayName("profile ratio backfill의 archive lineage와 maintenance 전용 권한을 생성한다")
    void createsProfileRatioBackfillLineageWithoutRuntimePrivileges() {
        flyway(null).clean();
        flyway(null).migrate();

        List<String> tables = jdbcClient.sql("""
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema='public'
                      AND table_name IN (
                        'building_ratio_profile_backfill_import',
                        'building_ratio_profile_candidate_lineage')
                    ORDER BY table_name
                    """).query(String.class).list();

        assertThat(tables)
                .containsExactly("building_ratio_profile_backfill_import", "building_ratio_profile_candidate_lineage");
        assertThat(jdbcClient.sql("""
                    SELECT constraint_name
                    FROM information_schema.table_constraints
                    WHERE table_schema='public'
                      AND table_name='building_ratio_profile_candidate_lineage'
                      AND constraint_name IN (
                        'ck_brpcl_safe_comparison',
                        'ck_brpcl_complete_contributors',
                        'ck_brpcl_ratio_values')
                    ORDER BY constraint_name
                    """).query(String.class).list())
                .containsExactly("ck_brpcl_complete_contributors", "ck_brpcl_ratio_values", "ck_brpcl_safe_comparison");
        assertThat(hasPrivilege("building_ratio_profile_backfill_import", "SELECT,INSERT,UPDATE,DELETE"))
                .isFalse();
        assertThat(hasPrivilege("building_ratio_profile_candidate_lineage", "SELECT,INSERT,UPDATE,DELETE"))
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
