package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.SignalModelUtility;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalScoreSignalStrength;
import com.home.news.NewsRuntimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalNewsResearchNoteGeneratorTest {

	private final HistoricalNewsResearchNoteGenerator generator = new HistoricalNewsResearchNoteGenerator(
		properties(),
		Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	@DisplayName("citation 없는 historical candidate는 Obsidian note로 생성하지 않는다")
	void skipsCandidateWithoutCitation(@TempDir Path tempDir) throws Exception {
		HistoricalNewsCandidate withCitation = candidate("https://example.com/article");
		HistoricalNewsCandidate withoutCitation = candidate("");

		HistoricalNewsNoteWriteResult result = generator.writeNotes(tempDir, List.of(withCitation, withoutCitation));

		assertThat(result.candidateCount()).isEqualTo(2);
		assertThat(result.noteCount()).isEqualTo(1);
		try (var walk = Files.walk(tempDir)) {
			assertThat(walk
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !path.toString().contains("_manifest"))
				.count()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("gate 통과 candidate만 yyyy-MM 경로의 Obsidian note로 생성하고 manifest에 reject reason count를 남긴다")
	void writesOnlyCandidatesAcceptedByGate(@TempDir Path tempDir) throws Exception {
		HistoricalNewsCandidate accepted = candidate("https://example.com/article");
		HistoricalNewsCandidate lowConfidence = accepted.withConfidence(new BigDecimal("0.790"));
		HistoricalNewsCandidate weakSignal = accepted.withUrl("https://example.com/weak")
			.withScoreSignalStrength(SignalScoreSignalStrength.WEAK);
		HistoricalNewsCandidate duplicate = accepted.withTitle("중복 URL 후보");

		HistoricalNewsNoteWriteResult result = generator.writeNotes(tempDir, List.of(
			accepted,
			lowConfidence,
			weakSignal,
			duplicate
		));

		assertThat(result.candidateCount()).isEqualTo(4);
		assertThat(result.noteCount()).isEqualTo(1);
		assertThat(result.rejectedCount()).isEqualTo(3);
		assertThat(result.rejectedByReason())
			.containsEntry(HistoricalNewsCandidateRejectReason.LOW_CONFIDENCE, 1)
			.containsEntry(HistoricalNewsCandidateRejectReason.WEAK_SIGNAL, 1)
			.containsEntry(HistoricalNewsCandidateRejectReason.DUPLICATE_URL, 1);

		Path notePath;
		try (var walk = Files.walk(tempDir)) {
			notePath = walk
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
		}
		assertThat(notePath.toString()).contains("news-research-seed/SEOUL_GANGNAM_GU/2020/2020-06/");

		String note = Files.readString(notePath);
		assertThat(note)
			.contains("query_month: 2020-06")
			.contains("query_bucket: SEOUL_GANGNAM_GU")
			.contains("model: gpt-5.4-2026-03-05")
			.contains("prompt_version: research-seed-v2-gpt54")
			.contains("schema_version: research-seed-schema-v2")
			.contains("screening_version: research-seed-screening-v1")
			.contains("score_signal_strength: STRONG")
			.contains("reason_codes: [policy]")
			.contains("candidate_hash:")
			.contains("- [ ] URL 접속 가능")
			.contains("- [ ] 기사 날짜가 query_month 내부")
			.contains("- [ ] 가격/전세/거래량/공급/risk 방향성이 설명 가능")
			.doesNotContain("Source link:");

		Path manifestJson;
		try (var walk = Files.walk(tempDir)) {
			manifestJson = walk
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.filter(path -> path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
		}
		assertThat(Files.readString(manifestJson))
			.contains("\"accepted\": 1")
			.contains("\"LOW_CONFIDENCE\":1")
			.contains("\"WEAK_SIGNAL\":1")
			.contains("\"DUPLICATE_URL\":1")
			.doesNotContain("중복 URL 후보");
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
			YearMonth.of(2020, 6),
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsSignalTopic.policy_regulation,
			SignalImpactTarget.sale_price,
			SignalImpactDirection.up,
			SignalScoreSignalStrength.STRONG,
			SignalModelUtility.HIGH,
			new BigDecimal("0.870"),
			List.of("policy")
		);

		generator.writeNotes(tempDir, List.of(candidate));
		Path notePath;
		try (var walk = Files.walk(tempDir)) {
			notePath = walk
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
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
			YearMonth.of(2020, 6),
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsRegionBucket.SEOUL_GANGNAM_GU,
			NewsSignalTopic.policy_regulation,
			SignalImpactTarget.sale_price,
			SignalImpactDirection.up,
			SignalScoreSignalStrength.STRONG,
			SignalModelUtility.HIGH,
			new BigDecimal("0.870"),
			List.of("policy")
		);
	}

	private NewsRuntimeProperties properties() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.getResearchSeed().setModel("gpt-5.4-2026-03-05");
		properties.getResearchSeed().setPromptVersion("research-seed-v2-gpt54");
		properties.getResearchSeed().setSchemaVersion("research-seed-schema-v2");
		properties.getResearchSeed().setScreeningVersion("research-seed-screening-v1");
		return properties;
	}
}
