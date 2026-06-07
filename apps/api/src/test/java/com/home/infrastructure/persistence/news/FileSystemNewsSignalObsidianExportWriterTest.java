package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemNewsSignalObsidianExportWriterTest {

	@TempDir
	private Path tempDir;

	@Test
	@DisplayName("news signal obsidian filesystem writer는 parent directory를 만들고 daily note를 덮어쓴다")
	void writesDailyNoteWithParentDirectoriesAndOverwrite() throws Exception {
		FileSystemNewsSignalObsidianExportWriter writer = new FileSystemNewsSignalObsidianExportWriter();

		Path path = writer.writeDailyNote(tempDir, LocalDate.parse("2026-06-07"), "first");
		Path overwrittenPath = writer.writeDailyNote(tempDir, LocalDate.parse("2026-06-07"), "second");

		assertThat(path).isEqualTo(tempDir.resolve("news-signals/daily/2026-06-07.md"));
		assertThat(overwrittenPath).isEqualTo(path);
		assertThat(Files.readString(path)).isEqualTo("second");
	}
}
