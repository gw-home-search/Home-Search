package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateImportOpsConfigurationTest {

	private static final Path SOURCE_DATA_ROOT = Path.of("../../apps/source-data");
	private static final Path COORDINATE_IMPORT_COMPOSE = SOURCE_DATA_ROOT.resolve("ops/docker-compose.coordinate-import.yml");
	private static final Path COORDINATE_SOURCE_DB_COMPOSE =
			SOURCE_DATA_ROOT.resolve("ops/docker-compose.coordinate-source-db.yml");
	private static final Path COORDINATE_IMPORT_SCRIPT =
			SOURCE_DATA_ROOT.resolve("ops/import-vworld-coordinate-snapshot.sh");
	private static final Path COORDINATE_SMOKE_SCRIPT =
			SOURCE_DATA_ROOT.resolve("ops/verify-coordinate-snapshot-smoke.sh");
	private static final Path COORDINATE_BOUNDARY_SCRIPT =
			SOURCE_DATA_ROOT.resolve("ops/verify-coordinate-source-boundary.sh");
	private static final Path COORDINATE_COPY_CUTOVER_SCRIPT =
			SOURCE_DATA_ROOT.resolve("ops/coordinate-source-db-copy-cutover.sh");
	private static final Path DAILY_BATCH_LIVE_SMOKE_SCRIPT = Path.of("ops/run-daily-batch-live-smoke.sh");
	private static final Path API_BASELINE_MIGRATION =
			Path.of("src/main/resources/db/migration/api/V1__create_clean_core_schema.sql");
	private static final Path COORDINATE_SOURCE_SCHEMA_SQL =
			SOURCE_DATA_ROOT.resolve("ops/sql/coordinate-source-schema.sql");
	private static final Path GEO_ENRICHMENT_MIGRATION =
			SOURCE_DATA_ROOT.resolve("src/main/resources/db/migration/geo-enrichment/V1__create_geo_enrichment_schema.sql");

	@Test
	@DisplayName("coordinate import compose override는 service key 없이 read-only SHP input을 연결한다")
	void coordinateImportComposeOverrideWiresReadOnlyShpInputWithoutSecrets() throws IOException {
		assertThat(COORDINATE_IMPORT_COMPOSE).exists();

		String content = Files.readString(COORDINATE_IMPORT_COMPOSE);
		String importer = serviceBlock(content, "coordinate-importer");
		String verifier = serviceBlock(content, "coordinate-smoke-verifier");

		assertThat(content).contains("coordinate-importer:");
		assertThat(importer).contains("image: postgis/postgis:16-3.4-alpine");
		assertThat(importer).contains("platform: ${HOME_COORDINATE_IMPORT_PLATFORM:-linux/amd64}");
		assertThat(content).contains("coordinate-import");
		assertThat(content).contains("HOME_COORDINATE_SHP_DIR: /coordinate-input");
		assertThat(content).contains("HOME_COORDINATE_INPUT_FORMAT: ${HOME_COORDINATE_INPUT_FORMAT:-auto}");
		assertThat(content).contains("HOME_COORDINATE_EXPECTED_REGIONS: ${HOME_COORDINATE_EXPECTED_REGIONS:-}");
		assertThat(content).contains("HOME_COORDINATE_STRICT_REGION_MATCH: ${HOME_COORDINATE_STRICT_REGION_MATCH:-true}");
		assertThat(content).contains("HOME_COORDINATE_VALIDATE_PRJ: ${HOME_COORDINATE_VALIDATE_PRJ:-true}");
		assertThat(content).contains("HOME_COORDINATE_SYNC_PARCEL: ${HOME_COORDINATE_SYNC_PARCEL:-false}");
		assertThat(content).contains("HOME_COORDINATE_RESUME_RUN_ID: ${HOME_COORDINATE_RESUME_RUN_ID:-}");
		assertThat(content).contains("HOME_COORDINATE_CHUNK_PREFIX_LENGTH: ${HOME_COORDINATE_CHUNK_PREFIX_LENGTH:-5}");
		assertThat(content).contains("${HOME_SEARCH_REPO_DIR:-..}:/workspace:ro");
		assertThat(content).contains("${HOME_COORDINATE_HOST_SHP_DIR:-../coordinate-input}:/coordinate-input:ro");
		assertThat(content).contains("bash\", \"/workspace/apps/source-data/ops/import-vworld-coordinate-snapshot.sh");
		assertThat(verifier).contains("image: postgis/postgis:16-3.4-alpine");
		assertThat(verifier).contains("platform: ${HOME_COORDINATE_IMPORT_PLATFORM:-linux/amd64}");
		assertThat(verifier)
			.contains("entrypoint: [\"bash\", \"/workspace/apps/source-data/ops/verify-coordinate-snapshot-smoke.sh\"]");
		assertThat(verifier).contains("PGHOST: postgis");
		assertThat(verifier).contains("PGPORT: \"5432\"");
		assertThat(verifier)
			.contains("PGDATABASE: ${COORDINATE_SOURCE_DB_NAME:-home_search_coordinate_source}");
		assertThat(verifier).contains("PGUSER: ${HOME_SEARCH_DB_USERNAME:-home_search}");
		assertThat(verifier).contains("PGPASSWORD: ${HOME_SEARCH_DB_PASSWORD:-home_search_local_password}");
		assertThat(verifier).contains("HOME_COORDINATE_EXPECTED_REGIONS: ${HOME_COORDINATE_EXPECTED_REGIONS:-}");
		assertThat(verifier).contains("HOME_COORDINATE_MIN_PNU_COUNT: ${HOME_COORDINATE_MIN_PNU_COUNT:-1}");
		assertThat(verifier).contains("HOME_COORDINATE_REQUIRE_SYNC_PARCEL: ${HOME_COORDINATE_REQUIRE_SYNC_PARCEL:-false}");
		assertThat(verifier).contains("HOME_COORDINATE_VERIFY_ACTIVE_COUNT: ${HOME_COORDINATE_VERIFY_ACTIVE_COUNT:-false}");
		assertThat(verifier).contains("${HOME_SEARCH_REPO_DIR:-..}:/workspace:ro");
		assertThat(verifier).doesNotContain("/coordinate-input");
		assertThat(content).doesNotContain("APT_SERVICE_KEY");
		assertThat(content).doesNotContain("VW_SERVICE_KEY");
		assertThat(content).doesNotContain(".env");
	}

	@Test
	@DisplayName("coordinate import script는 VWorld SHP preflight와 snapshot evidence check를 유지한다")
	void coordinateImportScriptKeepsPreflightAndEvidenceChecks() throws IOException {
		assertThat(COORDINATE_IMPORT_SCRIPT).exists();

		String content = Files.readString(COORDINATE_IMPORT_SCRIPT);

		assertThat(content).contains("LSMD_CONT_LDREG_*.shp");
		assertThat(content).contains("HOME_COORDINATE_REQUIRE_FULL_REGIONS");
		assertThat(content).contains("HOME_COORDINATE_ALLOW_MIXED_VERSION");
		assertThat(content).contains("HOME_COORDINATE_VALIDATE_PRJ");
		assertThat(content).contains("pg_try_advisory_lock");
		assertThat(content).contains("pg_advisory_unlock");
		assertThat(content).contains("LOCK_PSQL[1]+set");
		assertThat(content).contains("LOCK_PSQL_PID:-");
		assertThat(content).contains("\"${PSQL[@]}\" -q -At");
		assertThat(content).contains("coordinate_snapshot_run id must be numeric");
		assertThat(content).contains("ensure_shp2pgsql_runtime");
		assertThat(content).contains("apk add --no-cache gettext-libs");
		assertThat(content).contains("shp2pgsql >/dev/null 2>&1");
		assertThat(content).contains("Korea_Central_Belt_2010");
		assertThat(content).contains("GRS[_ ]?1980");
		assertThat(content).contains("reference.coordinate_snapshot_run");
		assertThat(content).contains("reference.parcel_coordinate_snapshot");
		assertThat(content).contains("AL_D010_*.shp");
		assertThat(content).contains("vworld-al-d010");
		assertThat(content).contains("--preflight-only");
		assertThat(content).contains("--self-test");
		assertThat(content).contains("A2 -> pnu");
		assertThat(content).contains("A23 -> source_region_code");
		assertThat(content).contains("HOME_COORDINATE_INPUT_FORMAT");
		assertThat(content).contains("HOME_COORDINATE_EXPECTED_REGIONS");
		assertThat(content).contains("HOME_COORDINATE_STRICT_REGION_MATCH");
		assertThat(content).contains("SYNC_PARCEL=\"${HOME_COORDINATE_SYNC_PARCEL:-false}\"");
		assertThat(content).contains("ST_PointOnSurface");
		assertThat(content).contains("ST_MakeValid");
		assertThat(content).contains("duplicate_pnu_count");
		assertThat(content).contains("CREATE UNLOGGED TABLE reference.land_parcel_snapshot_raw_next");
		assertThat(content).contains("reference.parcel_coordinate_snapshot_stage");
		assertThat(content).contains("reference.coordinate_snapshot_region_checkpoint");
		assertThat(content).contains("reference.parcel_coordinate_snapshot_publish");
		assertThat(content).contains("reference.coordinate_snapshot_publish_checkpoint");
		assertThat(content).contains("reference.coordinate_snapshot_stage_chunk_checkpoint");
		assertThat(content).contains("reference.coordinate_snapshot_publish_chunk_checkpoint");
		assertThat(content).contains("HOME_COORDINATE_RESUME_RUN_ID");
		assertThat(content).contains("HOME_COORDINATE_CHUNK_PREFIX_LENGTH");
		assertThat(content).contains("HOME_COORDINATE_SCHEMA_SQL");
		assertThat(content).contains("coordinate snapshot region import skipped");
		assertThat(content).contains("coordinate snapshot stage chunk skipped");
		assertThat(content).contains("coordinate snapshot stage chunk passed");
		assertThat(content).contains("coordinate snapshot publish chunk skipped");
		assertThat(content).contains("coordinate snapshot publish chunk passed");
		assertThat(content).contains("coordinate snapshot publish region passed");
		assertThat(content).contains("source_manifest");
		assertThat(content).contains("chunk_code");
		assertThat(content).doesNotContain("land_parcel_snapshot_raw_next_region_pnu_idx");
		assertThat(content).contains("SET LOCAL jit = off");
		assertThat(content).contains("SET LOCAL max_parallel_workers_per_gather = 0");
		assertThat(content).contains("for region_code in ${EXPECTED_REGIONS}; do");
		assertThat(content).contains("for chunk_code in ${chunk_codes}; do");
		assertThat(content).contains("SHP_REGION_CODES");
		assertThat(content).contains("for file_index in \"${!SHP_FILES[@]}\"; do");
		assertThat(content).contains("source_region_for_file");
		assertThat(content).contains("coordinate snapshot region import started");
		assertThat(content).contains("collect_file_stats");
		assertThat(content).contains("RAW_FEATURE_COUNT=\"$((RAW_FEATURE_COUNT + file_feature_count))\"");
		assertThat(content).contains("PNU_COUNT=\"$((PNU_COUNT + region_row_count))\"");
		assertThat(content).contains("coordinate snapshot raw staging cleanup started");
	}

	@Test
	@DisplayName("coordinate source schema SQL은 API Flyway 밖에서 durable stage와 checkpoint를 제공한다")
	void coordinateSnapshotResumableImportSchemaProvidesDurableStageAndCheckpoints() throws IOException {
		assertThat(COORDINATE_SOURCE_SCHEMA_SQL).exists();

		String content = Files.readString(COORDINATE_SOURCE_SCHEMA_SQL);

		assertThat(content).contains("CREATE TABLE reference.parcel_coordinate_snapshot_stage");
		assertThat(content).contains("CREATE TABLE reference.coordinate_snapshot_region_checkpoint");
		assertThat(content).contains("CREATE TABLE reference.coordinate_snapshot_stage_chunk_checkpoint");
		assertThat(content).contains("CREATE TABLE reference.parcel_coordinate_snapshot_publish");
		assertThat(content).contains("CREATE TABLE reference.coordinate_snapshot_publish_checkpoint");
		assertThat(content).contains("CREATE TABLE reference.coordinate_snapshot_publish_chunk_checkpoint");
		assertThat(content).contains("'STARTED'");
		assertThat(content).contains("'PASSED'");
		assertThat(content).contains("'FAILED'");
		assertThat(content).contains("source_manifest text NOT NULL");
		assertThat(content).contains("chunk_code character varying(8) NOT NULL");
		assertThat(content).contains("PRIMARY KEY (run_id, region_code)");
		assertThat(content).contains("PRIMARY KEY (run_id, region_code, chunk_code)");
		assertThat(content).contains("PRIMARY KEY (run_id, pnu)");
		assertThat(content).contains("USING gist (geom)");
		assertThat(content).contains("USING gist (point)");
	}

	@Test
	@DisplayName("API main Flyway resources는 coordinate source schema를 소유하지 않는다")
	void apiMainFlywayResourcesDoNotOwnCoordinateSourceSchema() {
		assertThat(Path.of("src/main/resources/db/migration/coordinate-source")).doesNotExist();
		assertThat(Path.of("src/main/resources/db/migration/api")).exists();
		assertThat(COORDINATE_SOURCE_SCHEMA_SQL).exists();
	}

	@Test
	@DisplayName("API clean baseline은 coordinate source reference schema를 생성하지 않는다")
	void apiCleanBaselineDoesNotCreateCoordinateSourceReferenceSchema() throws IOException {
		assertThat(API_BASELINE_MIGRATION).exists();
		assertThat(Path.of("src/main/resources/db/migration/api/V3__remove_operational_coordinate_source_reference.sql"))
			.doesNotExist();

		String content = Files.readString(API_BASELINE_MIGRATION);

		assertThat(content).doesNotContain("CREATE SCHEMA IF NOT EXISTS reference");
		assertThat(content).doesNotContain("CREATE TABLE reference.coordinate_snapshot_run");
		assertThat(content).doesNotContain("CREATE TABLE reference.parcel_coordinate_snapshot");
		assertThat(content).doesNotContain("reference.coordinate_snapshot_publish_checkpoint");
		assertThat(content).doesNotContain("reference.parcel_coordinate_snapshot_stage");
		assertThat(content).contains("CREATE TABLE public.building_footprint_snapshot");
	}

	@Test
	@DisplayName("coordinate source boundary verifier는 운영/source/geo DB 역할을 read-only로 확인한다")
	void coordinateSourceBoundaryVerifierChecksSeparatedDatabaseRolesReadOnly() throws IOException {
		assertThat(COORDINATE_BOUNDARY_SCRIPT).exists();

		String content = Files.readString(COORDINATE_BOUNDARY_SCRIPT);

		assertThat(content).contains("--operational");
		assertThat(content).contains("--source");
		assertThat(content).contains("--geo");
		assertThat(content).contains("Source DB checks avoid nationwide count(*) scans");
		assertThat(content).contains("runtime live snapshot tables");
		assertThat(content).contains("operational DB still owns coordinate source tables");
		assertThat(content).contains("source DB owns live reference coordinate snapshot tables");
		assertThat(content).contains("geo enrichment DB owns VWorld WFS raw/cache table");
		assertThat(content).contains("pg_total_relation_size");
		assertThat(content).contains("SET enable_seqscan = off");
		assertThat(content).contains("to_regclass('reference.coordinate_snapshot_run') IS NOT NULL");
		assertThat(content).contains("to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL");
		assertThat(content).doesNotContain("count(*) FROM reference.parcel_coordinate_snapshot");
		assertThat(content).doesNotContain("DROP TABLE");
		assertThat(content).doesNotContain("TRUNCATE");
	}

	@Test
	@DisplayName("coordinate source copy/cutover helper는 삭제 없이 dump, restore, 검증, env 전환 evidence를 만든다")
	void coordinateSourceCopyCutoverHelperKeepsSourceDatabaseRollbackable() throws IOException {
		assertThat(COORDINATE_COPY_CUTOVER_SCRIPT).exists();

		String content = Files.readString(COORDINATE_COPY_CUTOVER_SCRIPT);

		assertThat(content).contains("--copy-live-snapshot");
		assertThat(content).contains("--verify-live-snapshot");
		assertThat(content).contains("--verify-drop-readiness");
		assertThat(content).contains("--archive-import-worktables");
		assertThat(content).contains("--dump-source");
		assertThat(content).contains("--restore-copy");
		assertThat(content).contains("--print-cutover-env");
		assertThat(content).contains("HOME_COORDINATE_SOURCE_DB_HOST");
		assertThat(content).contains("HOME_COORDINATE_TARGET_DB_HOST");
		assertThat(content).contains("HOME_COORDINATE_TARGET_DB_PORT");
		assertThat(content).contains("HOME_COORDINATE_SOURCE_DB_CONTAINER");
		assertThat(content).contains("HOME_COORDINATE_TARGET_DB_CONTAINER");
		assertThat(content).contains("HOME_COORDINATE_POSTGIS_TOOL_IMAGE");
		assertThat(content).contains("HOME_COORDINATE_REQUIRE_WORKTABLE_ARCHIVE");
		assertThat(content).contains("docker exec -i");
		assertThat(content).contains("docker run --rm");
		assertThat(content).contains("--format=plain");
		assertThat(content).contains("--table=reference.coordinate_snapshot_run");
		assertThat(content).contains("--table=reference.parcel_coordinate_snapshot");
		assertThat(content).contains("--table=reference.parcel_coordinate_snapshot_stage");
		assertThat(content).contains("--table=reference.parcel_coordinate_snapshot_publish");
		assertThat(content).contains("psql -v ON_ERROR_STOP=1 --single-transaction");
		assertThat(content).contains("target DB already owns live coordinate source tables; refusing live restore");
		assertThat(content).contains("pg_dump");
		assertThat(content).contains("pg_restore");
		assertThat(content).contains("createdb");
		assertThat(content).contains("COORDINATE_SOURCE_DB_JDBC_URL");
		assertThat(content).contains("rollback: set COORDINATE_SOURCE_DB_JDBC_URL back");
		assertThat(content).contains("live_schema_fingerprint");
		assertThat(content).contains("target_snapshot_count");
		assertThat(content).contains("sample_lookup");
		assertThat(content).contains("source_active_connection_count");
		assertThat(content).contains("coordinate_source_relation_inventory");
		assertThat(content).contains("verify_chunked_worktable_archive");
		assertThat(content).contains("drop_readiness_worktable_archive_publish_chunks");
		assertThat(content).contains("drop_readiness_worktable_archive");
		assertThat(content).contains("drop_readiness_worktable_archive_checksum");
		assertThat(content).contains("sha256sum -c SHA256SUMS");
		assertThat(content).contains("shasum -a 256 -c SHA256SUMS");
		assertThat(content).contains("Live copy intentionally excludes import worktables");
		assertThat(content).doesNotContain("dropdb ");
		assertThat(content).doesNotContain("psql_db postgres -c \"DROP DATABASE");
		assertThat(content).doesNotContain("psql_db postgres -c \"DROP TABLE");
		assertThat(content).doesNotContain("psql_db postgres -c \"TRUNCATE");
		assertThat(content).doesNotContain("docker volume rm ");
		assertThat(content).doesNotContain("docker compose down -v ");
	}

	@Test
	@DisplayName("coordinate source DB compose는 source 전용 PostGIS와 별도 volume을 제공한다")
	void coordinateSourceDbComposeProvidesDedicatedPostgisAndVolume() throws IOException {
		assertThat(COORDINATE_SOURCE_DB_COMPOSE).exists();

		String content = Files.readString(COORDINATE_SOURCE_DB_COMPOSE);

		assertThat(content).contains("home-search-coordinate-source");
		assertThat(content).contains("container_name: home-search-coordinate-source-postgis");
		assertThat(content).contains("POSTGRES_DB: ${COORDINATE_SOURCE_TARGET_DB_NAME:-home_search_coordinate_source}");
		assertThat(content).contains("POSTGRES_USER: ${COORDINATE_SOURCE_TARGET_DB_USERNAME:-home_search}");
		assertThat(content).contains("POSTGRES_PASSWORD: ${COORDINATE_SOURCE_TARGET_DB_PASSWORD:-home_search_local_password}");
		assertThat(content).contains("${COORDINATE_SOURCE_TARGET_DB_PORT:-15435}:5432");
		assertThat(content).contains("source-postgis-data:/var/lib/postgresql/data");
		assertThat(content).contains("name: ${COORDINATE_SOURCE_TARGET_VOLUME_NAME:-home-search-coordinate-source-data}");
		assertThat(content).contains("external: true");
		assertThat(content).contains("name: home-search-local_home-search-local");
		assertThat(content).doesNotContain("home-search-local_home-search-postgis-data");
	}

	@Test
	@DisplayName("daily batch live smoke runner는 운영 DB를 coordinate source fallback으로 재사용하지 않는다")
	void dailyBatchLiveSmokeRunnerDoesNotReuseOperationalDbAsCoordinateSourceFallback() throws IOException {
		assertThat(DAILY_BATCH_LIVE_SMOKE_SCRIPT).exists();

		String content = Files.readString(DAILY_BATCH_LIVE_SMOKE_SCRIPT);

		assertThat(content).contains("DB_JDBC_URL=\"\\${DB_JDBC_URL:-jdbc:postgresql://localhost:15432/${DB_NAME}}\"");
		assertThat(content)
			.contains("COORDINATE_SOURCE_DB_JDBC_URL=\"\\${COORDINATE_SOURCE_DB_JDBC_URL:-jdbc:postgresql://localhost:15435/home_search_coordinate_source}\"");
		assertThat(content)
			.doesNotContain("COORDINATE_SOURCE_DB_JDBC_URL=\"\\${COORDINATE_SOURCE_DB_JDBC_URL:-jdbc:postgresql://localhost:15432/${DB_NAME}}\"");
	}

	@Test
	@DisplayName("geo enrichment migration은 VWorld WFS raw/cache를 운영 DB 밖에서 소유한다")
	void geoEnrichmentMigrationOwnsVworldWfsRawCacheOutsideOperationalDb() throws IOException {
		assertThat(GEO_ENRICHMENT_MIGRATION).exists();

		String content = Files.readString(GEO_ENRICHMENT_MIGRATION);

		assertThat(content).contains("CREATE SCHEMA IF NOT EXISTS geo_enrichment");
		assertThat(content).contains("CREATE TABLE geo_enrichment.vworld_wfs_footprint_cache");
		assertThat(content).contains("provider character varying(32) NOT NULL DEFAULT 'VWORLD_WFS'");
		assertThat(content).contains("raw_payload jsonb NOT NULL");
		assertThat(content).contains("public.geometry(MultiPolygon,4326)");
		assertThat(content).contains("uq_vworld_wfs_footprint_cache_source_key");
		assertThat(content).contains("USING gist (geom)");
	}

	@Test
	@DisplayName("coordinate import script는 bounded package layout과 상대경로 evidence를 지원한다")
	void coordinateImportScriptSupportsBoundedPackageLayoutAndRelativePathEvidence() throws IOException {
		assertThat(COORDINATE_IMPORT_SCRIPT).exists();

		String content = Files.readString(COORDINATE_IMPORT_SCRIPT);

		assertThat(content).contains("discover_shp_files");
		assertThat(content).contains("validate_package_layout");
		assertThat(content).contains("SHP_RELATIVE_PATHS");
		assertThat(content).contains("relativeFilePaths");
		assertThat(content).contains("duplicate SHP basenames");
		assertThat(content).contains("coordinate-input/AL_D010/<YYYYMMDD>/<sido>");
		assertThat(content).contains("coordinate-input/LSMD_CONT_LDREG/<YYYYMM>/<sido>");
		assertThat(content).contains("self-test passed: VWorld coordinate snapshot importer package layout");
		assertThat(content).doesNotContain("find_expr=");
	}

	@Test
	@DisplayName("coordinate full import smoke verifier는 최신 passed snapshot evidence를 확인한다")
	void coordinateFullImportSmokeVerifierChecksLatestPassedSnapshotEvidence() throws IOException {
		assertThat(COORDINATE_SMOKE_SCRIPT).exists();

		String content = Files.readString(COORDINATE_SMOKE_SCRIPT);

		assertThat(content).contains("HOME_COORDINATE_MIN_PNU_COUNT");
		assertThat(content).contains("HOME_COORDINATE_REQUIRE_SYNC_PARCEL");
		assertThat(content).contains("HOME_COORDINATE_VERIFY_ACTIVE_COUNT");
		assertThat(content).contains("reference.coordinate_snapshot_run");
		assertThat(content).contains("reference.parcel_coordinate_snapshot");
		assertThat(content).contains("status = 'PASSED'");
		assertThat(content).contains("region_count");
		assertThat(content).contains("pnu_count");
		assertThat(content).contains("invalid_count");
		assertThat(content).contains("duplicate_pnu_count");
		assertThat(content).contains("synced_parcel_count");
		assertThat(content).contains("missingRegions");
		assertThat(content).contains("ST_SRID(point) = 4326");
		assertThat(content).contains("ST_SRID(geom) = 4326");
		assertThat(content).contains("latitude BETWEEN 33 AND 39");
		assertThat(content).contains("longitude BETWEEN 124 AND 132");
		assertThat(content).contains("reference.coordinate_snapshot_publish_checkpoint");
		assertThat(content).contains("reference.coordinate_snapshot_publish_chunk_checkpoint");
		assertThat(content).contains("pg_constraint");
		assertThat(content).contains("parcel_coordinate_snapshot_latitude_check");
		assertThat(content).contains("parcel_coordinate_snapshot_longitude_check");
		assertThat(content).contains("SELECT count(*)::bigint");
		assertThat(content).contains("active_count_mode");
		assertThat(content).doesNotContain("count(*) FILTER (WHERE NOT ST_IsValid(geom))");
		assertThat(content).doesNotContain("string_agg(DISTINCT region_code");
		assertThat(content).contains("--self-test");
		assertThat(content).contains("coordinate snapshot smoke passed");
	}

	private static String serviceBlock(String content, String serviceName) {
		String marker = "  " + serviceName + ":";
		StringBuilder block = new StringBuilder();
		boolean capturing = false;
		for (String line : content.split("\n", -1)) {
			if (line.equals(marker)) {
				capturing = true;
			}
			else if (capturing && line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(":")) {
				break;
			}
			if (capturing) {
				block.append(line).append('\n');
			}
		}
		assertThat(block).isNotEmpty();
		return block.toString();
	}
}
