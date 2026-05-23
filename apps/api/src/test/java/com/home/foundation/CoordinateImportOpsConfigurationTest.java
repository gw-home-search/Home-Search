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

	@Test
	@DisplayName("coordinate import compose override wires read-only SHP input without service keys")
	void coordinateImportComposeOverrideWiresReadOnlyShpInputWithoutSecrets() throws IOException {
		assertThat(COORDINATE_IMPORT_COMPOSE).exists();

		String content = Files.readString(COORDINATE_IMPORT_COMPOSE);

		assertThat(content).contains("coordinate-importer:");
		assertThat(content).contains("postgis/postgis:16-3.4");
		assertThat(content).contains("coordinate-import");
		assertThat(content).contains("HOME_COORDINATE_SHP_DIR: /coordinate-input");
		assertThat(content).contains("HOME_COORDINATE_VALIDATE_PRJ: ${HOME_COORDINATE_VALIDATE_PRJ:-true}");
		assertThat(content).contains("${HOME_SEARCH_REPO_DIR:-..}:/workspace:ro");
		assertThat(content).contains("${HOME_COORDINATE_HOST_SHP_DIR:-../coordinate-input}:/coordinate-input:ro");
		assertThat(content).contains("bash\", \"/workspace/apps/api/ops/import-vworld-coordinate-snapshot.sh");
		assertThat(content).doesNotContain("APT_SERVICE_KEY");
		assertThat(content).doesNotContain("VW_SERVICE_KEY");
		assertThat(content).doesNotContain(".env");
	}

	@Test
	@DisplayName("coordinate import script keeps VWorld SHP preflight and snapshot evidence checks")
	void coordinateImportScriptKeepsPreflightAndEvidenceChecks() throws IOException {
		assertThat(COORDINATE_IMPORT_SCRIPT).exists();

		String content = Files.readString(COORDINATE_IMPORT_SCRIPT);

		assertThat(content).contains("LSMD_CONT_LDREG_*.shp");
		assertThat(content).contains("HOME_COORDINATE_REQUIRE_FULL_REGIONS");
		assertThat(content).contains("HOME_COORDINATE_ALLOW_MIXED_VERSION");
		assertThat(content).contains("HOME_COORDINATE_VALIDATE_PRJ");
		assertThat(content).contains("pg_try_advisory_lock");
		assertThat(content).contains("pg_advisory_unlock");
		assertThat(content).contains("Korea_Central_Belt_2010");
		assertThat(content).contains("GRS[_ ]?1980");
		assertThat(content).contains("reference.coordinate_snapshot_run");
		assertThat(content).contains("reference.parcel_coordinate_snapshot");
		assertThat(content).contains("ST_PointOnSurface");
		assertThat(content).contains("ST_MakeValid");
		assertThat(content).contains("duplicate_pnu_count");
	}
}
