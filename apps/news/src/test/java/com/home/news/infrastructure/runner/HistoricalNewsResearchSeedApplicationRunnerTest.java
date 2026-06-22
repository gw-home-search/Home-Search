package com.home.news.infrastructure.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.home.news.NewsRuntimeProperties;
import com.home.news.application.BigKindsCsvResearchNoteGenerator;
import com.home.news.application.HistoricalNewsCandidateRejectReason;
import com.home.news.application.HistoricalNewsCsvNoteWriteResult;
import com.home.news.application.HistoricalNewsCsvShortlistWriteResult;
import com.home.news.application.HistoricalNewsNoteWriteResult;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchNoteGenerator;
import com.home.news.application.HistoricalNewsResearchResult;
import com.home.news.application.HistoricalNewsSeedImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class HistoricalNewsResearchSeedApplicationRunnerTest {

	@Test
	@DisplayName("GENERATE_NOTES 실행 결과는 accepted/rejected/reason count와 output root를 로그로 남긴다")
	void logsGenerateNotesScreeningSummary(CapturedOutput output) {
		NewsRuntimeProperties properties = properties();
		HistoricalNewsResearchClient researchClient = request -> new HistoricalNewsResearchResult(List.of());
		HistoricalNewsResearchNoteGenerator noteGenerator = mock(HistoricalNewsResearchNoteGenerator.class);
		when(noteGenerator.writeNotes(any(), any())).thenReturn(new HistoricalNewsNoteWriteResult(
			5,
			0,
			0,
			5,
			Map.of(HistoricalNewsCandidateRejectReason.INVALID_URL, 5),
			Path.of("news-research-seed/obsidian")
		));
		HistoricalNewsSeedImporter importer = mock(HistoricalNewsSeedImporter.class);
		BigKindsCsvResearchNoteGenerator csvNoteGenerator = mock(BigKindsCsvResearchNoteGenerator.class);
		HistoricalNewsResearchSeedApplicationRunner runner = new HistoricalNewsResearchSeedApplicationRunner(
			researchClient,
			noteGenerator,
			csvNoteGenerator,
			importer,
			properties
		);

		runner.run(new DefaultApplicationArguments());

		assertThat(output)
			.contains("Historical research seed notes generated")
			.contains("candidates=5")
			.contains("accepted=0")
			.contains("notes=0")
			.contains("rejected=5")
			.contains("INVALID_URL=5")
			.contains("output_root=news-research-seed/obsidian");
	}

	@Test
	@DisplayName("GENERATE_CSV_NOTES는 CSV input dir에서 review note를 만들고 OpenAI research client를 호출하지 않는다")
	void generateCsvNotesModeUsesCsvGenerator(CapturedOutput output) {
		NewsRuntimeProperties properties = properties();
		properties.getResearchSeed().setMode("GENERATE_CSV_NOTES");
		properties.getResearchSeed().setCsvInputDir("apps/news/local-input/historical-news-csv");
		HistoricalNewsResearchClient researchClient = mock(HistoricalNewsResearchClient.class);
		HistoricalNewsResearchNoteGenerator noteGenerator = mock(HistoricalNewsResearchNoteGenerator.class);
		HistoricalNewsSeedImporter importer = mock(HistoricalNewsSeedImporter.class);
		BigKindsCsvResearchNoteGenerator csvNoteGenerator = mock(BigKindsCsvResearchNoteGenerator.class);
		when(csvNoteGenerator.writeNotes(any(), any())).thenReturn(new HistoricalNewsCsvNoteWriteResult(
			2,
			1,
			1,
			Map.of("MISSING_URL_COLUMNS", 1),
			Path.of("news-research-seed/obsidian")
		));
		HistoricalNewsResearchSeedApplicationRunner runner = new HistoricalNewsResearchSeedApplicationRunner(
			researchClient,
			noteGenerator,
			csvNoteGenerator,
			importer,
			properties
		);

		runner.run(new DefaultApplicationArguments());

		verify(csvNoteGenerator).writeNotes(Path.of("apps/news/local-input/historical-news-csv"), Path.of("news-research-seed/obsidian"));
		verify(researchClient, never()).research(any());
		assertThat(output)
			.contains("BigKinds CSV research seed notes generated")
			.contains("files=2")
			.contains("notes=1")
			.contains("skipped_files=1")
			.contains("MISSING_URL_COLUMNS=1");
	}

	@Test
	@DisplayName("GENERATE_CSV_SHORTLIST는 manifest/report만 만들고 OpenAI/note/import를 호출하지 않는다")
	void generateCsvShortlistModeUsesCsvShortlistGenerator(CapturedOutput output) {
		NewsRuntimeProperties properties = properties();
		properties.getResearchSeed().setMode("GENERATE_CSV_SHORTLIST");
		properties.getResearchSeed().setCsvInputDir("apps/news/local-input/historical-news-csv");
		HistoricalNewsResearchClient researchClient = mock(HistoricalNewsResearchClient.class);
		HistoricalNewsResearchNoteGenerator noteGenerator = mock(HistoricalNewsResearchNoteGenerator.class);
		HistoricalNewsSeedImporter importer = mock(HistoricalNewsSeedImporter.class);
		BigKindsCsvResearchNoteGenerator csvNoteGenerator = mock(BigKindsCsvResearchNoteGenerator.class);
		when(csvNoteGenerator.writeShortlists(any(), any())).thenReturn(new HistoricalNewsCsvShortlistWriteResult(
			2,
			1,
			20,
			1,
			Map.of("MISSING_URL_COLUMNS", 1),
			Path.of("news-research-seed/obsidian")
		));
		HistoricalNewsResearchSeedApplicationRunner runner = new HistoricalNewsResearchSeedApplicationRunner(
			researchClient,
			noteGenerator,
			csvNoteGenerator,
			importer,
			properties
		);

		runner.run(new DefaultApplicationArguments());

		verify(csvNoteGenerator).writeShortlists(Path.of("apps/news/local-input/historical-news-csv"), Path.of("news-research-seed/obsidian"));
		verify(researchClient, never()).research(any());
		verify(noteGenerator, never()).writeNotes(any(), any());
		verify(importer, never()).importApprovedNotes(any());
		assertThat(output)
			.contains("BigKinds CSV research seed shortlist generated")
			.contains("files=2")
			.contains("months=1")
			.contains("candidates=20")
			.contains("skipped_files=1")
			.contains("MISSING_URL_COLUMNS=1");
	}

	private NewsRuntimeProperties properties() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.getResearchSeed().setEnabled(true);
		properties.getResearchSeed().setMode("GENERATE_NOTES");
		properties.getResearchSeed().setMaxRequestsPerRun(1);
		properties.getResearchSeed().setTargetCandidatesPerBucket(5);
		properties.getResearchSeed().setOutputDir("news-research-seed/obsidian");
		return properties;
	}
}
