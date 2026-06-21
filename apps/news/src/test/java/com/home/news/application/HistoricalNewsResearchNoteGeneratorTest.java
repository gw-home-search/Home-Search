package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalNewsResearchNoteGeneratorTest {

	private final HistoricalNewsResearchNoteGenerator generator = new HistoricalNewsResearchNoteGenerator();

	@Test
	@DisplayName("citation 없는 historical candidate는 Obsidian note로 생성하지 않는다")
	void skipsCandidateWithoutCitation(@TempDir Path tempDir) throws Exception {
		HistoricalNewsCandidate withCitation = candidate("https://example.com/article");
		HistoricalNewsCandidate withoutCitation = candidate("");

		HistoricalNewsNoteWriteResult result = generator.writeNotes(tempDir, List.of(withCitation, withoutCitation));

		assertThat(result.candidateCount()).isEqualTo(2);
		assertThat(result.noteCount()).isEqualTo(1);
		try (var walk = Files.walk(tempDir)) {
			assertThat(walk.filter(path -> path.getFileName().toString().endsWith(".md")).count()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("candidate text 줄바꿈과 frontmatter delimiter는 note에 그대로 쓰지 않는다")
	void sanitizesCandidateTextForMarkdown(@TempDir Path tempDir) throws Exception {
		HistoricalNewsCandidate candidate = new HistoricalNewsCandidate(
			"강남\n---\nevil: true",
			"Example\nDaily",
			LocalDate.of(2020, 6, 2),
			"https://example.com/article",
			"https://example.com/article",
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsSignalTopic.policy_regulation,
			SignalImpactTarget.sale_price,
			SignalImpactDirection.up,
			"high\nutility",
			new BigDecimal("0.870"),
			List.of("policy\nreason")
		);

		generator.writeNotes(tempDir, List.of(candidate));
		Path notePath;
		try (var walk = Files.walk(tempDir)) {
			notePath = walk.filter(path -> path.getFileName().toString().endsWith(".md")).findFirst().orElseThrow();
		}

		String note = Files.readString(notePath);

		assertThat(note).doesNotContain("\nevil: true");
		assertThat(note).contains("강남 - - - evil: true");
	}

	private HistoricalNewsCandidate candidate(String citation) {
		return new HistoricalNewsCandidate(
			"강남 재건축 규제 완화",
			"Example Daily",
			LocalDate.of(2020, 6, 2),
			"https://example.com/article?utm_source=test",
			citation,
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsSignalTopic.policy_regulation,
			SignalImpactTarget.sale_price,
			SignalImpactDirection.up,
			"high",
			new BigDecimal("0.870"),
			List.of("policy")
		);
	}
}
