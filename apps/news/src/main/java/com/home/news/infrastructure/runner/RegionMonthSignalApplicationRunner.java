package com.home.news.infrastructure.runner;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.List;

import com.home.domain.news.RegionMonthSignalRunMode;
import com.home.news.NewsRuntimeProperties;
import com.home.news.application.BigKindsCsvRegionMonthSignalGenerator;
import com.home.news.application.NewsSignalValidationException;
import com.home.news.application.RegionMonthSignalImporter;
import com.home.news.application.RegionMonthSignalJsonl;
import com.home.news.application.RegionMonthSignalObsidianExporter;
import com.home.news.application.RegionMonthSignalSnapshot;
import com.home.news.application.RegionMonthSignalWebWorklistGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class RegionMonthSignalApplicationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RegionMonthSignalApplicationRunner.class);

	private final BigKindsCsvRegionMonthSignalGenerator csvGenerator;
	private final RegionMonthSignalJsonl jsonl;
	private final RegionMonthSignalWebWorklistGenerator worklistGenerator;
	private final ObjectProvider<RegionMonthSignalImporter> importer;
	private final ObjectProvider<RegionMonthSignalObsidianExporter> exporter;
	private final NewsRuntimeProperties properties;

	public RegionMonthSignalApplicationRunner(
		BigKindsCsvRegionMonthSignalGenerator csvGenerator,
		RegionMonthSignalJsonl jsonl,
		RegionMonthSignalWebWorklistGenerator worklistGenerator,
		ObjectProvider<RegionMonthSignalImporter> importer,
		ObjectProvider<RegionMonthSignalObsidianExporter> exporter,
		NewsRuntimeProperties properties
	) {
		this.csvGenerator = csvGenerator;
		this.jsonl = jsonl;
		this.worklistGenerator = worklistGenerator;
		this.importer = importer;
		this.exporter = exporter;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		NewsRuntimeProperties.RegionMonthSignals signals = properties.getRegionMonthSignals();
		RegionMonthSignalRunMode mode = mode(signals.getMode());
		if (mode == RegionMonthSignalRunMode.GENERATE_CSV_REGION_MONTH_SIGNALS) {
			List<RegionMonthSignalSnapshot> snapshots = csvGenerator.generate(path(signals.getCsvInputDir()), signals.getMethodVersion());
			jsonl.write(path(signals.getGeneratedCsvSignalsPath()), snapshots);
			int worklistCount = worklistGenerator.write(
				path(signals.getWebWorklistPath()),
				YearMonth.of(2021, 4),
				YearMonth.of(2026, 5)
			);
			log.info("region-month CSV signals generated: rows={} worklist_rows={}", snapshots.size(), worklistCount);
			return;
		}
		if (mode == RegionMonthSignalRunMode.VALIDATE_WEB_REGION_MONTH_SIGNALS) {
			int rows = jsonl.read(path(signals.getWebResearchPath())).size();
			log.info("region-month web research JSONL valid: rows={}", rows);
			return;
		}
		if (mode == RegionMonthSignalRunMode.IMPORT_REGION_MONTH_SIGNALS) {
			RegionMonthSignalImporter activeImporter = importer.getIfAvailable(() -> {
				throw new NewsSignalValidationException("IMPORT_REGION_MONTH_SIGNALS requires database configuration");
			});
			activeImporter.importJsonl(path(signals.getGeneratedCsvSignalsPath()));
			activeImporter.importJsonl(path(signals.getWebResearchPath()));
			log.info("region-month signals imported");
			return;
		}
		if (mode == RegionMonthSignalRunMode.EXPORT_REGION_MONTH_SIGNALS) {
			RegionMonthSignalObsidianExporter activeExporter = exporter.getIfAvailable(() -> {
				throw new NewsSignalValidationException("EXPORT_REGION_MONTH_SIGNALS requires database configuration");
			});
			int months = activeExporter.export(path(signals.getObsidianOutputDir()));
			log.info("region-month signal Obsidian export complete: months={}", months);
		}
	}

	private RegionMonthSignalRunMode mode(String mode) {
		try {
			return RegionMonthSignalRunMode.valueOf(mode);
		}
		catch (IllegalArgumentException ex) {
			throw new NewsSignalValidationException("home.news.region-month-signals.mode is invalid: " + mode, ex);
		}
	}

	private Path path(String configuredPath) {
		Path path = Path.of(configuredPath);
		String prefix = "apps/news/";
		if (configuredPath.startsWith(prefix)) {
			Path appRelative = Path.of(configuredPath.substring(prefix.length()));
			if (Files.exists(Path.of("src/main/java/com/home/news/NewsApplication.java"))) {
				return appRelative;
			}
			if (Files.exists(appRelative) || (appRelative.getParent() != null && Files.exists(appRelative.getParent()))) {
				return appRelative;
			}
		}
		if (path.isAbsolute() || Files.exists(path)) {
			return path;
		}
		if (path.getParent() != null && Files.exists(path.getParent())) {
			return path;
		}
		return path;
	}
}
