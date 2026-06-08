package com.home.application.news.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import com.home.application.news.signal.NewsSignalDatasetRow;

public class NewsSignalObsidianExportService {

	private final NewsSignalObsidianExportRepository repository;
	private final NewsSignalObsidianMarkdownRenderer renderer;
	private final NewsSignalObsidianExportWriter writer;

	public NewsSignalObsidianExportService(
		NewsSignalObsidianExportRepository repository,
		NewsSignalObsidianMarkdownRenderer renderer,
		NewsSignalObsidianExportWriter writer
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
		this.writer = Objects.requireNonNull(writer, "writer must not be null");
	}

	public NewsSignalObsidianExportResult exportDaily(NewsSignalObsidianExportCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		List<NewsSignalDatasetRow> queriedRows = repository.findObservedBetween(
			command.startInclusive(),
			command.endExclusive(),
			command.maxRows() + 1
		);
		boolean truncated = queriedRows.size() > command.maxRows();
		List<NewsSignalDatasetRow> exportRows = queriedRows.stream()
			.limit(command.maxRows())
			.toList();
		String markdown = renderer.renderDailyNote(command, exportRows, truncated);
		Path path = writer.writeDailyNote(command.outputRoot(), command.date(), markdown);
		int articleCount = (int) exportRows.stream()
			.mapToLong(NewsSignalDatasetRow::articleObservationId)
			.distinct()
			.count();
		return new NewsSignalObsidianExportResult(
			command.date(),
			path,
			exportRows.size(),
			articleCount,
			truncated
		);
	}
}
