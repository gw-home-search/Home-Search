package com.home.news.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.home.news.infrastructure.persistence.JdbcRegionMonthSignalRepository;

public class RegionMonthSignalObsidianExporter {

	private final JdbcRegionMonthSignalRepository repository;

	public RegionMonthSignalObsidianExporter(JdbcRegionMonthSignalRepository repository) {
		this.repository = repository;
	}

	public int export(Path outputRoot) {
		List<RegionMonthSignalSnapshot> snapshots = repository.findAllSnapshots();
		Map<YearMonth, List<RegionMonthSignalSnapshot>> byMonth = snapshots.stream()
			.collect(Collectors.groupingBy(snapshot -> YearMonth.from(snapshot.signalMonth()), TreeMap::new, Collectors.toList()));
		Path targetDir = outputRoot.resolve("news-research-seed").resolve("region-month-signals");
		try {
			Files.createDirectories(targetDir);
			for (Map.Entry<YearMonth, List<RegionMonthSignalSnapshot>> entry : byMonth.entrySet()) {
				Files.writeString(targetDir.resolve(entry.getKey() + ".md"), markdown(entry.getKey(), entry.getValue()), StandardCharsets.UTF_8);
			}
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to export region-month signal notes", ex);
		}
		return byMonth.size();
	}

	private String markdown(YearMonth month, List<RegionMonthSignalSnapshot> snapshots) {
		StringBuilder builder = new StringBuilder();
		builder.append("---\n");
		builder.append("type: region_month_signal\n");
		builder.append("signal_month: ").append(month).append("\n");
		builder.append("region_count: ").append(snapshots.size()).append("\n");
		builder.append("---\n\n");
		builder.append("# ").append(month).append(" region month signals\n\n");
		builder.append("| region_bucket | source_kind | confidence | up | down | matched | direct | inherited | note |\n");
		builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
		snapshots.stream()
			.sorted(Comparator.comparing(snapshot -> snapshot.regionBucket().name()))
			.forEach(snapshot -> {
				ForbiddenNewsTextGuard.rejectForbiddenText("aggregate_note", snapshot.aggregateNote());
				builder.append("| ")
					.append(snapshot.regionBucket().name()).append(" | ")
					.append(snapshot.sourceKind().name()).append(" | ")
					.append(snapshot.confidence()).append(" | ")
					.append(snapshot.priceUpSignal()).append(" | ")
					.append(snapshot.priceDownSignal()).append(" | ")
					.append(snapshot.matchedNewsCount()).append(" | ")
					.append(snapshot.directEvidenceCount()).append(" | ")
					.append(snapshot.inheritedEvidenceCount()).append(" | ")
					.append(tableText(snapshot.aggregateNote())).append(" |\n");
			});
		builder.append("\n## evidence links\n\n");
		for (RegionMonthSignalSnapshot snapshot : snapshots) {
			for (RegionMonthSignalEvidence evidence : snapshot.evidence()) {
				ForbiddenNewsTextGuard.rejectForbiddenText("evidence.title", evidence.title());
				builder.append("- `")
					.append(snapshot.regionBucket().name())
					.append("` ")
					.append(evidence.publishedDate() == null ? "" : evidence.publishedDate())
					.append(" ")
					.append(inlineText(evidence.publisher()))
					.append(" - [")
					.append(linkLabel(evidence.title()))
					.append("](")
					.append(linkUrl(evidence.citationUrl() == null ? evidence.url() : evidence.citationUrl()))
					.append(")\n");
			}
		}
		return builder.toString();
	}

	private String tableText(String value) {
		return inlineText(value).replace("|", "/");
	}

	private String inlineText(String value) {
		if (value == null) {
			return "";
		}
		return value.replace('\r', ' ').replace('\n', ' ').strip();
	}

	private String linkLabel(String value) {
		return inlineText(value).replace("[", "(").replace("]", ")");
	}

	private String linkUrl(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return inlineText(value).replace(")", "%29").replace(" ", "%20");
	}
}
