package com.home.application.news;

import java.nio.file.Path;
import java.time.LocalDate;

public interface NewsSignalObsidianExportWriter {

	Path writeDailyNote(Path outputRoot, LocalDate date, String markdown);
}
