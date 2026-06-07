package com.home.application.news;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class NewsSignalObsidianMarkdownRenderer {

	private static final DateTimeFormatter OFFSET_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	public String renderDailyNote(
		NewsSignalObsidianExportCommand command,
		List<NewsSignalDatasetRow> rows,
		boolean truncated
	) {
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(rows, "rows must not be null");
		List<NewsSignalDatasetRow> sortedRows = rows.stream()
			.sorted(Comparator
				.comparing(NewsSignalDatasetRow::firstSeenAt)
				.thenComparingLong(NewsSignalDatasetRow::featureId))
			.toList();
		StringBuilder builder = new StringBuilder();
		appendFrontMatter(builder, command, sortedRows, truncated);
		builder.append('\n');
		builder.append("# News Signals - ").append(command.date()).append("\n\n");
		builder.append("## Signals\n");
		for (NewsSignalDatasetRow row : sortedRows) {
			appendSignal(builder, command, row);
		}
		return builder.toString();
	}

	private void appendFrontMatter(
		StringBuilder builder,
		NewsSignalObsidianExportCommand command,
		List<NewsSignalDatasetRow> rows,
		boolean truncated
	) {
		builder.append("---\n");
		builder.append("date: ").append(command.date()).append('\n');
		builder.append("timezone: ").append(command.zoneId()).append('\n');
		builder.append("first_seen_from_inclusive: ").append(format(command.startInclusive())).append('\n');
		builder.append("first_seen_before_exclusive: ").append(format(command.endExclusive())).append('\n');
		builder.append("generated_from: news_signal_dataset_view\n");
		builder.append("feature_count: ").append(rows.size()).append('\n');
		builder.append("article_count: ").append(articleCount(rows)).append('\n');
		builder.append("publishers: ").append(yamlArray(publishers(rows))).append('\n');
		builder.append("regions: ").append(yamlArray(flatten(rows.stream()
			.map(NewsSignalDatasetRow::regionTags)
			.toList()))).append('\n');
		builder.append("topics: ").append(yamlArray(flatten(rows.stream()
			.map(NewsSignalDatasetRow::topicTags)
			.toList()))).append('\n');
		builder.append("truncated: ").append(truncated).append('\n');
		builder.append("---\n");
	}

	private void appendSignal(StringBuilder builder, NewsSignalObsidianExportCommand command, NewsSignalDatasetRow row) {
		builder.append("- ").append(markdownLink(row)).append('\n');
		builder.append("  - publisher: ").append(safeLine(row.publisher())).append('\n');
		builder.append("  - source: ").append(safeLine(row.source())).append('\n');
		builder.append("  - source_key: ").append(safeLine(row.sourceKey())).append('\n');
		builder.append("  - first_seen_at: ").append(format(row.firstSeenAt().atZoneSameInstant(command.zoneId())
			.toOffsetDateTime())).append('\n');
		builder.append("  - published_at: ").append(formatNullable(row.publishedAt(), command)).append('\n');
		builder.append("  - regions: ").append(join(row.regionTags())).append('\n');
		builder.append("  - topics: ").append(join(row.topicTags())).append('\n');
		builder.append("  - impact: ").append(safeLine(row.impactTarget()))
			.append(" / ")
			.append(safeLine(row.impactDirection()))
			.append('\n');
		builder.append("  - sentiment: ").append(safeLine(row.sentiment())).append('\n');
		builder.append("  - confidence: ").append(String.format(Locale.ROOT, "%.2f", row.confidence())).append('\n');
		builder.append("  - evidence_level: ").append(safeLine(row.evidenceLevel())).append('\n');
		builder.append("  - extraction_version: ").append(safeLine(row.extractionVersion())).append('\n');
	}

	private int articleCount(List<NewsSignalDatasetRow> rows) {
		return (int) rows.stream()
			.mapToLong(NewsSignalDatasetRow::articleObservationId)
			.distinct()
			.count();
	}

	private Set<String> publishers(List<NewsSignalDatasetRow> rows) {
		return rows.stream()
			.map(NewsSignalDatasetRow::publisher)
			.map(this::safeLine)
			.filter(value -> !value.isBlank())
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private Set<String> flatten(Collection<List<String>> values) {
		return values.stream()
			.flatMap(Collection::stream)
			.map(this::safeLine)
			.filter(value -> !value.isBlank())
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private String yamlArray(Collection<String> values) {
		return values.stream()
			.map(this::safeLine)
			.collect(Collectors.joining(", ", "[", "]"));
	}

	private String markdownLink(NewsSignalDatasetRow row) {
		String title = safeLine(row.title());
		String url = firstNonBlank(row.url(), row.providerUrl());
		if (url.isBlank()) {
			return title;
		}
		return "[" + escapeMarkdownLinkText(title) + "](<" + url.replace(">", "%3E") + ">)";
	}

	private String join(Collection<String> values) {
		return values.stream()
			.map(this::safeLine)
			.filter(value -> !value.isBlank())
			.collect(Collectors.joining(", "));
	}

	private String formatNullable(OffsetDateTime value, NewsSignalObsidianExportCommand command) {
		if (value == null) {
			return "";
		}
		return format(value.atZoneSameInstant(command.zoneId()).toOffsetDateTime());
	}

	private String format(OffsetDateTime value) {
		return OFFSET_DATE_TIME.format(value);
	}

	private String firstNonBlank(String first, String second) {
		String safeFirst = safeLine(first);
		if (!safeFirst.isBlank()) {
			return safeFirst;
		}
		return safeLine(second);
	}

	private String safeLine(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("\\R", " ").trim();
	}

	private String escapeMarkdownLinkText(String value) {
		return value.replace("[", "\\[").replace("]", "\\]");
	}
}
