package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateImportOpsConfigurationTest {

	private static final Path COORDINATE_IMPORT_COMPOSE = Path.of("ops/docker-compose.coordinate-import.yml");
	private static final Path COORDINATE_IMPORT_SCRIPT = Path.of("ops/import-vworld-coordinate-snapshot.sh");
	private static final Path COORDINATE_SMOKE_SCRIPT = Path.of("ops/verify-coordinate-snapshot-smoke.sh");
	private static final Path WORKLOG = Path.of("../../.codex/harness/worklog.toml");

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
		assertThat(content).contains("${HOME_SEARCH_REPO_DIR:-..}:/workspace:ro");
		assertThat(content).contains("${HOME_COORDINATE_HOST_SHP_DIR:-../coordinate-input}:/coordinate-input:ro");
		assertThat(content).contains("bash\", \"/workspace/apps/api/ops/import-vworld-coordinate-snapshot.sh");
		assertThat(verifier).contains("image: postgis/postgis:16-3.4-alpine");
		assertThat(verifier).contains("platform: ${HOME_COORDINATE_IMPORT_PLATFORM:-linux/amd64}");
		assertThat(verifier).contains("entrypoint: [\"bash\", \"/workspace/apps/api/ops/verify-coordinate-snapshot-smoke.sh\"]");
		assertThat(verifier).contains("PGHOST: postgis");
		assertThat(verifier).contains("PGPORT: \"5432\"");
		assertThat(verifier).contains("PGDATABASE: ${HOME_SEARCH_DB_NAME:-home_search}");
		assertThat(verifier).contains("PGUSER: ${HOME_SEARCH_DB_USERNAME:-home_search}");
		assertThat(verifier).contains("PGPASSWORD: ${HOME_SEARCH_DB_PASSWORD:-home_search_local_password}");
		assertThat(verifier).contains("HOME_COORDINATE_EXPECTED_REGIONS: ${HOME_COORDINATE_EXPECTED_REGIONS:-}");
		assertThat(verifier).contains("HOME_COORDINATE_MIN_PNU_COUNT: ${HOME_COORDINATE_MIN_PNU_COUNT:-1}");
		assertThat(verifier).contains("HOME_COORDINATE_REQUIRE_SYNC_PARCEL: ${HOME_COORDINATE_REQUIRE_SYNC_PARCEL:-false}");
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
		assertThat(content).contains("ST_PointOnSurface");
		assertThat(content).contains("ST_MakeValid");
		assertThat(content).contains("duplicate_pnu_count");
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
		assertThat(content).contains("--self-test");
		assertThat(content).contains("coordinate snapshot smoke passed");
	}

	@Test
	@DisplayName("worklog는 coordinate full import smoke 작업을 등록한다")
	void worklogRegistersCoordinateFullImportSmokeWork() throws IOException {
		assertThat(WORKLOG).exists();

		String content = Files.readString(WORKLOG);

		assertThat(content).contains("id = \"baseline-coordinate-full-import-smoke\"");
		assertThat(content).contains("status = \"done\"");
		assertThat(content).contains("preset = \"coordinate-snapshot-import\"");
		assertThat(content).contains("targets = \"backend\"");
		assertThat(content).contains("verify-coordinate-snapshot-smoke.sh --self-test");
		assertThat(content).contains("HOME_COORDINATE_EXPECTED_REGIONS");
		assertThat(content).contains("coordinate_snapshot_run.status = PASSED");
		assertThat(content).contains("full national import");
	}

	@Test
	@DisplayName("worklog는 coordinate snapshot storage verification 작업을 등록한다")
	void worklogRegistersCoordinateSnapshotStorageVerificationWork() throws IOException {
		assertThat(WORKLOG).exists();

		String content = Files.readString(WORKLOG);

		assertThat(content).contains("id = \"coordinate-snapshot-storage-verification\"");
		assertThat(content).contains("pr_type = \"Test\"");
		assertThat(content).contains("preset = \"coordinate-snapshot-import\"");
		assertThat(content).contains("targets = \"backend\"");
		assertThat(content).contains("compose coordinate-importer는 bash, psql, shp2pgsql");
		assertThat(content).contains("verify-coordinate-snapshot-smoke.sh --self-test");
		assertThat(content).contains("coordinate_snapshot_run.status = PASSED");
		assertThat(content).contains("active reference.parcel_coordinate_snapshot row count");
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
