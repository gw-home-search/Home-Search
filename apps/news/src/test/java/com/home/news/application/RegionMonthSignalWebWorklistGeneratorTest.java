package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsRegionBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionMonthSignalWebWorklistGeneratorTest {

	@Test
	@DisplayName("web research worklist는 row마다 metadata evidence target 5개를 명시한다")
	void writesEvidenceTarget(@TempDir Path tempDir) throws Exception {
		Path outputPath = tempDir.resolve("region-month-signal-web-worklist.jsonl");
		int rows = new RegionMonthSignalWebWorklistGenerator(new ObjectMapper())
			.write(outputPath, YearMonth.of(2022, 1), YearMonth.of(2022, 1));

		String content = Files.readString(outputPath);

		assertThat(rows).isEqualTo(NewsRegionBucket.values().length);
		assertThat(content)
			.contains("\"evidence_target\":5")
			.contains("metadata links only")
			.doesNotContain("body")
			.doesNotContain("full_text")
			.doesNotContain("article_summary");
	}
}
