package com.home.infrastructure.persistence.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

import com.home.application.news.NewsSignalObsidianExportWriter;

class FileSystemNewsSignalObsidianExportWriter implements NewsSignalObsidianExportWriter {

	@Override
	public Path writeDailyNote(Path outputRoot, LocalDate date, String markdown) {
		Path dailyNote = outputRoot.resolve("news-signals").resolve("daily").resolve(date + ".md");
		try {
			Files.createDirectories(dailyNote.getParent());
			Files.writeString(
				dailyNote,
				markdown,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);
			return dailyNote;
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to write news signal Obsidian daily note", exception);
		}
	}
}
