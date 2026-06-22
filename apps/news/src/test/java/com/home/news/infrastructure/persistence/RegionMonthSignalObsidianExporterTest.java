package com.home.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalSourceKind;
import com.home.news.application.RegionMonthSignalObsidianExporter;
import com.home.news.application.RegionMonthSignalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

class RegionMonthSignalObsidianExporterTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void migrate() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
	}

	@Test
	@DisplayName("Obsidian export는 월별 note에 26개 region row를 쓴다")
	void exportsMonthlyNotesWithAllRegionRows(@TempDir Path tempDir) throws Exception {
		JdbcRegionMonthSignalRepository repository = new JdbcRegionMonthSignalRepository(jdbcClient, new ObjectMapper());
		long runId = repository.startImportRun(RegionMonthSignalSourceKind.BIGKINDS_CSV, "test-method-v1", NewsModelDatasetTier.EXPERIMENTAL_SEED, "test.jsonl");
		for (RegionMonthSignalSnapshot snapshot : twoMonths()) {
			long snapshotId = repository.upsertSnapshot(snapshot, runId);
			repository.replaceEvidence(snapshotId, snapshot.evidence());
		}

		int monthCount = new RegionMonthSignalObsidianExporter(repository).export(tempDir);

		assertThat(monthCount).isEqualTo(2);
		Path targetDir = tempDir.resolve("news-research-seed").resolve("region-month-signals");
		assertThat(Files.list(targetDir).filter(path -> path.getFileName().toString().endsWith(".md")).toList()).hasSize(2);
		String june = Files.readString(targetDir.resolve("2020-06.md"));
		assertThat(june)
			.contains("region_count: 26")
			.contains("SEOUL_GANGNAM_GU")
			.doesNotContain("content")
			.doesNotContain("full_text")
			.doesNotContain("article_summary");
		assertThat(june.lines().filter(line -> line.matches("\\| [A-Z_]+ .*")).count()).isEqualTo(26);
	}

	private List<RegionMonthSignalSnapshot> twoMonths() {
		List<RegionMonthSignalSnapshot> snapshots = new ArrayList<>();
		for (LocalDate month : List.of(LocalDate.of(2020, 6, 1), LocalDate.of(2020, 7, 1))) {
			for (NewsRegionBucket bucket : NewsRegionBucket.values()) {
				snapshots.add(new RegionMonthSignalSnapshot(
					bucket,
					month,
					RegionMonthSignalSourceKind.BIGKINDS_CSV,
					"test-method-v1",
					NewsModelDatasetTier.EXPERIMENTAL_SEED,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					0,
					new BigDecimal("0.300"),
					bucket.titleKo() + " " + month + " metadata signal 없음",
					List.of()
				));
			}
		}
		return snapshots;
	}
}
