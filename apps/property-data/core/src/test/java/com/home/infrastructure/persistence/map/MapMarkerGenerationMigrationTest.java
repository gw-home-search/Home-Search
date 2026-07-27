package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MapMarkerGenerationMigrationTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("검증된 marker generation은 단일 pointer 전환으로 활성화된다")
    void validatedGenerationIsActivatedThroughSingletonPointer() {
        long firstGenerationId = insertGeneration("VALIDATED", "watermark-1");
        long secondGenerationId = insertGeneration("VALIDATED", "watermark-2");

        jdbcClient.sql("""
			INSERT INTO map_marker_active_generation (singleton_id, generation_id)
			VALUES (1, :generationId)
			""").param("generationId", firstGenerationId).update();

        jdbcClient.sql("""
			UPDATE map_marker_active_generation
			SET generation_id = :generationId,
			    activated_at = now()
			WHERE singleton_id = 1
			""").param("generationId", secondGenerationId).update();

        assertThat(jdbcClient
                        .sql("SELECT generation_id FROM map_marker_active_generation WHERE singleton_id = 1")
                        .query(Long.class)
                        .single())
                .isEqualTo(secondGenerationId);
        assertThat(jdbcClient
                        .sql("SELECT status FROM map_marker_generation WHERE id = :generationId")
                        .param("generationId", firstGenerationId)
                        .query(String.class)
                        .single())
                .isEqualTo("RETIRED");
        assertThat(jdbcClient
                        .sql("SELECT status FROM map_marker_generation WHERE id = :generationId")
                        .param("generationId", secondGenerationId)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("직전 retired marker generation은 pointer 전환만으로 다시 활성화할 수 있다")
    void retiredGenerationCanBeReactivatedForRollback() {
        long firstGenerationId = insertGeneration("VALIDATED", "watermark-1");
        long secondGenerationId = insertGeneration("VALIDATED", "watermark-2");

        activate(firstGenerationId);
        activate(secondGenerationId);
        activate(firstGenerationId);

        assertThat(jdbcClient
                        .sql("SELECT generation_id FROM map_marker_active_generation WHERE singleton_id = 1")
                        .query(Long.class)
                        .single())
                .isEqualTo(firstGenerationId);
        assertThat(jdbcClient
                        .sql("SELECT status FROM map_marker_generation WHERE id = :generationId")
                        .param("generationId", firstGenerationId)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
        assertThat(jdbcClient
                        .sql("SELECT status FROM map_marker_generation WHERE id = :generationId")
                        .param("generationId", secondGenerationId)
                        .query(String.class)
                        .single())
                .isEqualTo("RETIRED");
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

    private long insertGeneration(String status, String watermark) {
        return jdbcClient
                .sql("""
				INSERT INTO map_marker_generation (
				    status,
				    source_watermark,
				    complex_marker_count,
				    region_marker_count,
				    marker_hash,
				    validated_at
				)
				VALUES (:status, :watermark, 0, 0, repeat('a', 64), now())
				RETURNING id
				""")
                .param("status", status)
                .param("watermark", watermark)
                .query(Long.class)
                .single();
    }
}
