package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcBuildingMetadataMigrationTest extends JdbcMigrationTestSupport {
    @Test
    @DisplayName("V7은 신규 테이블 없이 건축물대장 identity와 attempt 추적 컬럼만 추가한다")
    void addsOnlyMinimalBuildingMetadataFields() {
        flyway(MigrationVersion.fromVersion("6")).clean();
        flyway(MigrationVersion.fromVersion("6")).migrate();
        seedComplex(501, 1001, "1168010300101400001");
        flyway(null).migrate();

        assertThat(column("complex", "bld_mgm_bld_rgst_pk")).isEqualTo("character varying");
        assertThat(column("complex_metadata_enrichment_attempt", "request_id")).isEqualTo("uuid");
        assertThat(column("complex_metadata_enrichment_attempt", "projection_applied"))
                .isEqualTo("boolean");
        assertThat(regclass("complex_metadata_source_snapshot")).isNull();
        assertThat(regclass("complex_metadata_snapshot_evaluation")).isNull();
        assertThat(regclass("complex_external_identity")).isNull();
        assertThat(regclass("complex_building_metadata_state")).isNull();
        assertThat(regclass("complex_building_metadata_decision")).isNull();
    }

    @Test
    @DisplayName("건축물대장 관리번호는 non-blank이며 단지 간 중복할 수 없다")
    void rejectsBlankAndDuplicateBuildingRegisterKey() {
        flyway(null).clean();
        flyway(null).migrate();
        seedComplex(501, 1001, "1168010300101400001");
        seedComplex(502, 1002, "1168010300101400002");
        jdbcClient
                .sql("UPDATE complex SET bld_mgm_bld_rgst_pk='BLD-1' WHERE id=501")
                .update();
        assertThatThrownBy(() -> jdbcClient
                        .sql("UPDATE complex SET bld_mgm_bld_rgst_pk='BLD-1' WHERE id=502")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient
                        .sql("UPDATE complex SET bld_mgm_bld_rgst_pk=' ' WHERE id=502")
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void seedComplex(long id, long parcelId, String pnu) {
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (:pid,:pnu,'Sample')")
                .param("pid", parcelId)
                .param("pnu", pnu)
                .update();
        jdbcClient
                .sql("INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES (:id,:pid,:pk,'Sample Apartment')")
                .param("id", id)
                .param("pid", parcelId)
                .param("pk", "RTMS:" + id)
                .update();
    }

    private String column(String table, String column) {
        return jdbcClient
                .sql("SELECT data_type FROM information_schema.columns WHERE table_name=:table AND column_name=:column")
                .param("table", table)
                .param("column", column)
                .query(String.class)
                .single();
    }

    private String regclass(String name) {
        return jdbcClient
                .sql("SELECT to_regclass(:name)::text")
                .param("name", name)
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
