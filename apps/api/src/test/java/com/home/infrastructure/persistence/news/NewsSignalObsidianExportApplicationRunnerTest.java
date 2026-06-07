package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.home.application.news.NewsSignalObsidianExportCommand;
import com.home.application.news.NewsSignalObsidianExportResult;
import com.home.application.news.NewsSignalObsidianExportService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NewsSignalObsidianExportApplicationRunnerTest {

	@Test
	@DisplayName("news signal obsidian export runner는 disabled 설정이면 export를 실행하지 않는다")
	void disabledRunnerDoesNotExport() {
		NewsSignalObsidianExportService service = mock(NewsSignalObsidianExportService.class);
		NewsSignalObsidianExportApplicationRunner runner = new NewsSignalObsidianExportApplicationRunner(
			service,
			new NewsSignalObsidianExportProperties(false, Path.of("/tmp/obsidian"), null, ZoneId.of("Asia/Seoul"), 100),
			Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"))
		);

		runner.run(null);

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("news signal obsidian export runner는 enabled 설정이면 configured date로 daily export를 실행한다")
	void enabledRunnerExportsConfiguredDate() {
		NewsSignalObsidianExportService service = mock(NewsSignalObsidianExportService.class);
		when(service.exportDaily(any()))
			.thenReturn(new NewsSignalObsidianExportResult(
				LocalDate.parse("2026-06-07"),
				Path.of("/tmp/obsidian/news-signals/daily/2026-06-07.md"),
				3,
				2,
				false
			));
		NewsSignalObsidianExportApplicationRunner runner = new NewsSignalObsidianExportApplicationRunner(
			service,
			new NewsSignalObsidianExportProperties(
				true,
				Path.of("/tmp/obsidian"),
				LocalDate.parse("2026-06-07"),
				ZoneId.of("Asia/Seoul"),
				25
			),
			Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"))
		);

		runner.run(null);

		ArgumentCaptor<NewsSignalObsidianExportCommand> commandCaptor =
			ArgumentCaptor.forClass(NewsSignalObsidianExportCommand.class);
		verify(service).exportDaily(commandCaptor.capture());
		assertThat(commandCaptor.getValue().date()).isEqualTo(LocalDate.parse("2026-06-07"));
		assertThat(commandCaptor.getValue().maxRows()).isEqualTo(25);
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.NEWS_OBSIDIAN_EXPORT);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.NEWS_OBSERVATION_CLEANUP);
	}

	@Test
	@DisplayName("news signal obsidian export runner는 date 설정이 없으면 KST 오늘 날짜로 daily export를 실행한다")
	void enabledRunnerExportsKstTodayWhenDateIsNotConfigured() {
		NewsSignalObsidianExportService service = mock(NewsSignalObsidianExportService.class);
		when(service.exportDaily(any()))
			.thenReturn(new NewsSignalObsidianExportResult(
				LocalDate.parse("2026-06-07"),
				Path.of("/tmp/obsidian/news-signals/daily/2026-06-07.md"),
				0,
				0,
				false
			));
		NewsSignalObsidianExportApplicationRunner runner = new NewsSignalObsidianExportApplicationRunner(
			service,
			new NewsSignalObsidianExportProperties(
				true,
				Path.of("/tmp/obsidian"),
				null,
				ZoneId.of("Asia/Seoul"),
				25
			),
			Clock.fixed(Instant.parse("2026-06-06T15:30:00Z"), ZoneId.of("UTC"))
		);

		runner.run(null);

		ArgumentCaptor<NewsSignalObsidianExportCommand> commandCaptor =
			ArgumentCaptor.forClass(NewsSignalObsidianExportCommand.class);
		verify(service).exportDaily(commandCaptor.capture());
		assertThat(commandCaptor.getValue().date()).isEqualTo(LocalDate.parse("2026-06-07"));
	}
}
