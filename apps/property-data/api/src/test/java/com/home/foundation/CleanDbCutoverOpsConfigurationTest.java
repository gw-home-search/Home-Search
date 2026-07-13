package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CleanDbCutoverOpsConfigurationTest {

	private static final Path CLEAN_DB_CUTOVER_SCRIPT = Path.of("..", "ops", "verify-clean-db-cutover.sh");
	private static final Path API_MIGRATION_DIRECTORY =
		Path.of("..", "db", "migration", "api");

	@Test
	@DisplayName("clean DB cutover verifier는 데이터 비교와 cleanup table 부재를 확인한다")
	void cleanDbCutoverVerifierChecksDataParityAndRemovedTables() throws IOException {
		assertThat(CLEAN_DB_CUTOVER_SCRIPT).exists();

		String content = Files.readString(CLEAN_DB_CUTOVER_SCRIPT);

		assertThat(content).contains("HOME_CLEAN_CUTOVER_LEGACY_DB");
		assertThat(content).contains("HOME_CLEAN_CUTOVER_CLEAN_DB");
		assertThat(content).contains("home_search_clean_codex_20260616");
		assertThat(content).contains("flyway_schema_history");
		assertThat(content).contains("rtms_backfill_job");
		assertThat(content).contains("complex_relation_case");
		assertThat(content).contains("reference.parcel_coordinate_snapshot");
		assertThat(content).contains("pg_constraint");
		assertThat(content).contains("NOT convalidated");
		assertThat(content).contains("raw_trade_ingest");
		assertThat(content).contains("trade_source_key_registry");
		assertThat(content).contains("trade_match_evidence");
		assertThat(content).contains("diff -u");
		assertThat(content).contains("--exact-counts");
		assertThat(content).contains("verification_mode=\"max-id\"");
		assertThat(content).contains("verification_mode=\"exact-counts\"");
		assertThat(content).contains("--self-test");
		assertThat(content).doesNotContain("news_article_observation");
		assertThat(content).doesNotContain("news_signal_feature");
		assertThat(content).doesNotContain("news_collection_run");
	}

	@Test
	@DisplayName("clean DB cutover verifier는 현재 API Flyway migration 목록을 기준으로 history를 검증한다")
	void cleanDbCutoverVerifierUsesCurrentApiFlywayMigrationVersions() throws IOException {
		assertThat(CLEAN_DB_CUTOVER_SCRIPT).exists();
		assertThat(API_MIGRATION_DIRECTORY).isDirectory();

		String content = Files.readString(CLEAN_DB_CUTOVER_SCRIPT);

		assertThat(content).contains("expected_flyway_versions");
		assertThat(content).contains("db/migration/api/V*.sql");
		assertThat(content).contains("${flyway_versions}\" != \"${expected_versions}");
		assertThat(content).doesNotContain("flyway_versions\" != \"1:true,2:true\"");
	}

	@Test
	@DisplayName("clean DB cutover verifier는 백업과 quarantine rename 전에는 기존 DB 삭제를 막는다")
	void cleanDbCutoverVerifierRequiresBackupAndExplicitConfirmationBeforeDestructiveSteps() throws IOException {
		assertThat(CLEAN_DB_CUTOVER_SCRIPT).exists();

		String content = Files.readString(CLEAN_DB_CUTOVER_SCRIPT);

		assertThat(content).contains("--backup-legacy");
		assertThat(content).contains("pg_dump -U");
		assertThat(content).contains("pg_restore -l");
		assertThat(content).contains("--quarantine-rename");
		assertThat(content).contains("--confirm-rename");
		assertThat(content).contains("--accept-max-id-evidence");
		assertThat(content).contains("quarantine rename requires --exact-counts or --accept-max-id-evidence");
		assertThat(content).contains("ALTER DATABASE");
		assertThat(content).contains("--drop-legacy");
		assertThat(content).contains("--confirm-drop-legacy");
		assertThat(content).contains("home_search_legacy_before_clean_");
		assertThat(content).contains("refusing to drop active legacy or clean DB name");
		assertThat(content).contains("active DB connections must be closed");
		assertThat(content).doesNotContain("docker volume rm");
		assertThat(content).doesNotContain("docker volume prune");
		assertThat(content).doesNotContain("docker system prune");
		assertThat(content).doesNotContain("docker compose down -v");
	}
}
