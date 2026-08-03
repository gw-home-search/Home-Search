package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerResult;
import com.home.application.map.RegionMarkerQuery;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcMapMarkerProjectionIntegrationTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("legacy 집계와 active read-model의 public marker 및 canonical hash가 일치한다")
    void projectionMatchesLegacyPublicMarkersAndCanonicalHash() {
        seedPropertyExplorationData();
        LegacyJdbcMapMarkerRepository legacyRepository = new LegacyJdbcMapMarkerRepository(jdbcClient);
        JdbcMapMarkerProjectionWriter writer = new JdbcMapMarkerProjectionWriter(jdbcClient, transactionTemplate);
        JdbcMapMarkerRepository projectionRepository = new JdbcMapMarkerRepository(jdbcClient);

        List<ComplexMarkerResult> legacyMarkers = legacyRepository.findComplexMarkers(bounds());
        var generation = writer.rebuildAndActivate("trade:parity");
        List<ComplexMarkerResult> projectionMarkers = projectionRepository.findComplexMarkers(bounds());

        assertThat(projectionMarkers).isEqualTo(legacyMarkers);
        assertThat(generation.markerHash()).isEqualTo(canonicalHash(legacyMarkers));
    }

    @Test
    @DisplayName("marker projection은 일반 runtime 제한을 바꾸지 않고 전용 transaction timeout을 사용한다")
    void projectionUsesTransactionLocalStatementTimeout() {
        seedPropertyExplorationData();
        jdbcClient.sql("""
			CREATE FUNCTION require_map_projection_timeout_for_test()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $function$
			BEGIN
			    IF current_setting('statement_timeout') <> '3min' THEN
			        RAISE EXCEPTION 'projection statement_timeout must be transaction-local 3min';
			    END IF;
			    RETURN NEW;
			END
			$function$
			""").update();
        jdbcClient.sql("""
			CREATE TRIGGER require_map_projection_timeout_for_test
			BEFORE INSERT ON map_complex_marker_projection
			FOR EACH ROW EXECUTE FUNCTION require_map_projection_timeout_for_test()
			""").update();
        try {
            JdbcMapMarkerProjectionWriter writer =
                    new JdbcMapMarkerProjectionWriter(jdbcClient, new DataSourceTransactionManager(dataSource));

            writer.rebuildAndActivate("trade:timeout-scope");

            assertThat(jdbcClient
                            .sql("SELECT current_setting('statement_timeout')")
                            .query(String.class)
                            .single())
                    .isNotEqualTo("3min");
        } finally {
            jdbcClient
                    .sql("DROP TRIGGER require_map_projection_timeout_for_test ON map_complex_marker_projection")
                    .update();
            jdbcClient.sql("DROP FUNCTION require_map_projection_timeout_for_test()")
                    .update();
        }
    }

    @Test
    @DisplayName("새 generation 구축 실패는 기존 active pointer를 보존하고 실패 evidence를 남긴다")
    void failedGenerationKeepsPreviousActivePointer() {
        seedPropertyExplorationData();
        JdbcMapMarkerProjectionWriter writer =
                new JdbcMapMarkerProjectionWriter(jdbcClient, new DataSourceTransactionManager(dataSource));
        long activeGenerationId = writer.rebuildAndActivate("trade:stable").generationId();
        jdbcClient.sql("""
			CREATE FUNCTION fail_new_map_projection_for_test()
			RETURNS trigger
			LANGUAGE plpgsql
			AS $function$
			BEGIN
			    IF NEW.generation_id <> %d THEN
			        RAISE EXCEPTION 'forced projection failure';
			    END IF;
			    RETURN NEW;
			END
			$function$
			""".formatted(activeGenerationId)).update();
        jdbcClient.sql("""
			CREATE TRIGGER fail_new_map_projection_for_test
			BEFORE INSERT ON map_complex_marker_projection
			FOR EACH ROW EXECUTE FUNCTION fail_new_map_projection_for_test()
			""").update();
        try {
            transactionTemplate.executeWithoutResult(
                    status -> assertThatThrownBy(() -> writer.rebuildAndActivate("trade:broken"))
                            .isInstanceOf(RuntimeException.class));

            assertThat(writer.activeGenerationId()).isEqualTo(activeGenerationId);
            assertThat(jdbcClient
                            .sql("SELECT count(*) FROM map_marker_generation WHERE status = 'FAILED'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
        } finally {
            jdbcClient
                    .sql("DROP TRIGGER fail_new_map_projection_for_test ON map_complex_marker_projection")
                    .update();
            jdbcClient.sql("DROP FUNCTION fail_new_map_projection_for_test()").update();
        }
    }

    @Test
    @DisplayName("active generation은 source 변경과 분리되고 검증된 다음 generation 전환 후에만 바뀐다")
    void activeGenerationChangesOnlyAfterValidatedPointerSwitch() {
        seedPropertyExplorationData();
        jdbcClient.sql("UPDATE region SET unit_cnt_sum = 740 WHERE id = 11").update();
        JdbcMapMarkerProjectionWriter writer = new JdbcMapMarkerProjectionWriter(jdbcClient, transactionTemplate);
        JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);
        JdbcRegionMarkerRepository regionRepository = new JdbcRegionMarkerRepository(jdbcClient);

        var first = writer.rebuildAndActivate("trade:9002");

        assertThat(repository.findComplexMarkers(bounds()))
                .singleElement()
                .satisfies(marker -> assertThat(marker.latestDealAmount()).isEqualTo(130000L));

        jdbcClient.sql("UPDATE trade SET deal_amount = 140000 WHERE id = 9002").update();
        jdbcClient.sql("UPDATE region SET unit_cnt_sum = 999 WHERE id = 11").update();

        assertThat(repository.findComplexMarkers(bounds()))
                .singleElement()
                .satisfies(marker -> assertThat(marker.latestDealAmount()).isEqualTo(130000L));
        assertThat(regionRepository.findRegionMarkers(regionBounds()))
                .singleElement()
                .satisfies(marker -> assertThat(marker.unitCntSum()).isEqualTo(740L));

        var second = writer.rebuildAndActivate("trade:9002:corrected");

        assertThat(second.generationId()).isGreaterThan(first.generationId());
        assertThat(repository.findComplexMarkers(bounds()))
                .singleElement()
                .satisfies(marker -> assertThat(marker.latestDealAmount()).isEqualTo(140000L));
        assertThat(regionRepository.findRegionMarkers(regionBounds()))
                .singleElement()
                .satisfies(marker -> assertThat(marker.unitCntSum()).isEqualTo(999L));
    }

    private ComplexMarkerQuery bounds() {
        return new ComplexMarkerQuery(37.45, 126.85, 37.70, 127.20, null, null, null, null, null, null, null, null);
    }

    private RegionMarkerQuery regionBounds() {
        return new RegionMarkerQuery(37.45, 126.85, 37.70, 127.20, "si-gun-gu");
    }

    private String canonicalHash(List<ComplexMarkerResult> markers) {
        String canonicalRows = markers.stream()
                .map(marker -> String.join(
                        "|",
                        marker.parcelId().toString(),
                        marker.complexId() == null ? "" : marker.complexId().toString(),
                        marker.name() == null ? "" : marker.name(),
                        marker.lat().toString(),
                        marker.lng().toString(),
                        marker.latestDealAmount() == null
                                ? ""
                                : marker.latestDealAmount().toString(),
                        marker.unitCntSum() == null ? "" : marker.unitCntSum().toString()))
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonicalRows.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
