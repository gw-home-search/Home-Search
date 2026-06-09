package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendPersistencePackageStructureTest {

	private static final Path INGEST_PERSISTENCE_ROOT =
		Path.of("src/main/java/com/home/infrastructure/persistence/ingest");
	private static final int MAX_ROOT_CLASSES = 5;
	private static final int MIN_CAPABILITY_PACKAGES = 4;

	@Test
	@DisplayName("ingest persistence adapter는 capability package로 분리된다")
	void ingestPersistenceAdaptersAreSplitByCapabilityPackage() throws IOException {
		List<String> violations = new java.util.ArrayList<>();
		long rootClassCount;
		try (var rootFiles = Files.list(INGEST_PERSISTENCE_ROOT)) {
			rootClassCount = rootFiles
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.getFileName().toString().endsWith("PersistenceConfiguration.java"))
				.filter(path -> !path.getFileName().toString().equals("IngestPersistenceJdbcSupport.java"))
				.filter(path -> !path.getFileName().toString().equals("package-info.java"))
				.count();
		}
		long subpackageCount;
		try (var rootEntries = Files.list(INGEST_PERSISTENCE_ROOT)) {
			subpackageCount = rootEntries.filter(Files::isDirectory).count();
		}

		if (rootClassCount > MAX_ROOT_CLASSES) {
			violations.add("ingest persistence root has " + rootClassCount + " classes");
		}
		if (subpackageCount < MIN_CAPABILITY_PACKAGES) {
			violations.add("ingest persistence capability packages are missing");
		}

		assertThat(violations).isEmpty();
	}
}
