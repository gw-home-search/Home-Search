package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.home.news.NewsRuntimeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BigKindsCsvResearchNoteGeneratorTest {

	private static final String HEADER_18 = "주소,일자,언론사,기고자,제목,통합 분류1,통합 분류2,통합 분류3,사건_사고 분류1,사건_사고 분류2,사건_사고 분류3,개체명(인물),개체명(지역),개체명(기업기관),키워드,특성추출,본문,원본주소";
	private final BigKindsCsvResearchNoteGenerator generator = new BigKindsCsvResearchNoteGenerator(
		properties(),
		Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	@DisplayName("utf-8-sig comma BigKinds 18컬럼 export를 body 없이 v2 Obsidian note로 생성한다")
	void writesUtf8CommaCsvRowsAsReviewNotes(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Path outputRoot = tempDir.resolve("obsidian");
		Files.createDirectories(inputDir);
		Files.writeString(inputDir.resolve("부동산 (2020.04.01-2020.06.30).csv"), "\uFEFF" + HEADER_18 + "\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001,2020-06-02,경인일보,기자,\"강남 재건축 규제 완화\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"재건축,강남,아파트\",\"재건축,규제완화,강남\",저장하면 안 되는 본문,https://example.com/article\n");

		HistoricalNewsCsvNoteWriteResult result = generator.writeNotes(inputDir, outputRoot);

		assertThat(result.generatedCount()).isEqualTo(1);
		assertThat(result.skippedFileCount()).isZero();
		Path notePath;
		try (var walk = Files.walk(outputRoot)) {
			notePath = walk
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
		}
		String note = Files.readString(notePath);
		assertThat(note)
			.contains("source: BIGKINDS_CSV")
			.contains("discovery_method: PROVIDER_EXPORT")
			.contains("availability_basis: LICENSED_HISTORICAL_EXPORT")
			.contains("verification_status: NEEDS_REVIEW")
			.contains("published_date: 2020-06-02")
			.contains("url: \"https://example.com/article\"")
			.contains("url_citation: \"http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001\"")
			.contains("signal_month: 2020-06")
			.contains("source_file: 부동산 (2020.04.01-2020.06.30).csv")
			.contains("source_row_number: 2")
			.contains("provider_record_id: 01200201.20200602123456001")
			.contains("original_url: \"https://example.com/article\"")
			.contains("검수 참고")
			.contains("키워드: 재건축,강남,아파트")
			.contains("특성추출: 재건축,규제완화,강남")
			.doesNotContain("본문")
			.doesNotContain("저장하면 안 되는 본문")
			.doesNotContain("query_month:")
			.doesNotContain("model:")
			.doesNotContain("score_signal_strength:");
	}

	@Test
	@DisplayName("cp949 comma/tab 18컬럼은 처리하고 URL 없는 6컬럼 export는 MISSING_URL_COLUMNS로 skip한다")
	void detectsEncodingDelimiterAndSkipsSixColumnCsv(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Path outputRoot = tempDir.resolve("obsidian");
		Files.createDirectories(inputDir);
		String row = "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01500151.20191001060714001\t2019-10-01\t경남도민일보\t기자\t도내 미분양 줄었지만 누적 물량 해소 언제쯤\t경제>부동산\t\t\t\t\t\t\t경남\t국토교통부\t미분양,아파트,주택\t미분양,경남,아파트\t저장하면 안 되는 본문\thttps://example.com/unsold\n";
		Files.writeString(inputDir.resolve("cp949-tab.csv"), HEADER_18.replace(',', '\t') + "\n" + row, Charset.forName("MS949"));
		Files.writeString(inputDir.resolve("six-column.csv"), "일자,언론사,제목,키워드,특성추출,본문\n2021-04-01,신문사,강남 아파트 규제,강남,규제,본문\n", Charset.forName("MS949"));

		HistoricalNewsCsvNoteWriteResult result = generator.writeNotes(inputDir, outputRoot);

		assertThat(result.generatedCount()).isEqualTo(1);
		assertThat(result.skippedFileCount()).isEqualTo(1);
		assertThat(result.skippedByReason()).containsEntry("MISSING_URL_COLUMNS", 1);
		Path manifest;
		try (var walk = Files.walk(outputRoot)) {
			manifest = walk
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.filter(path -> path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
		}
		assertThat(Files.readString(manifest))
			.contains("\"MISSING_URL_COLUMNS\":1")
			.doesNotContain("본문")
			.doesNotContain("저장하면 안 되는 본문");
	}

	@Test
	@DisplayName("월별 shortlist는 번호/source_key 매핑과 metadata-only manifest/report만 생성한다")
	void writesMonthlyShortlistManifestWithoutNotesOrBody(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Path outputRoot = tempDir.resolve("obsidian");
		Files.createDirectories(inputDir);
		NewsRuntimeProperties properties = properties();
		properties.getResearchSeed().setPeriodStart(java.time.LocalDate.of(2020, 6, 1));
		properties.getResearchSeed().setPeriodEnd(java.time.LocalDate.of(2020, 6, 30));
		properties.getResearchSeed().setCsvShortlistLimit(1);
		BigKindsCsvResearchNoteGenerator shortlistGenerator = new BigKindsCsvResearchNoteGenerator(
			properties,
			Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
		);
		Files.writeString(inputDir.resolve("shortlist.csv"), "\uFEFF" + HEADER_18 + "\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001,2020-06-02,경인일보,기자,\"강남 재건축 규제 완화\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"재건축,강남,아파트\",\"재건축,규제완화,강남\",저장하면 안 되는 본문,https://example.com/article?utm_source=test\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200702123456001,2020-07-02,경인일보,기자,\"강남 아파트 거래량 증가\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"거래량,강남,아파트\",\"거래량,강남\",다른 본문,https://example.com/july\n");

		HistoricalNewsCsvShortlistWriteResult result = shortlistGenerator.writeShortlists(inputDir, outputRoot);

		assertThat(result.fileCount()).isEqualTo(1);
		assertThat(result.monthCount()).isEqualTo(1);
		assertThat(result.candidateCount()).isEqualTo(1);
		Path manifestRoot = outputRoot.resolve("news-research-seed").resolve("_manifest");
		Path jsonPath;
		Path markdownPath;
		try (var walk = Files.walk(manifestRoot)) {
			jsonPath = walk
				.filter(path -> path.getFileName().toString().startsWith("csv-shortlist-2020-06-"))
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.findFirst()
				.orElseThrow();
		}
		try (var walk = Files.walk(manifestRoot)) {
			markdownPath = walk
				.filter(path -> path.getFileName().toString().startsWith("csv-shortlist-2020-06-"))
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.findFirst()
				.orElseThrow();
		}
		String json = Files.readString(jsonPath);
		String markdown = Files.readString(markdownPath);
		assertThat(json)
			.contains("\"number\": 1")
			.contains("\"source_key\": \"BIGKINDS_CSV:01200201.20200602123456001\"")
			.contains("\"source_file\": \"shortlist.csv\"")
			.contains("\"source_row_number\": 2")
			.contains("\"provider_record_id\": \"01200201.20200602123456001\"")
			.contains("\"title\": \"강남 재건축 규제 완화\"")
			.contains("\"published_date\": \"2020-06-02\"")
			.contains("\"url\": \"https://example.com/article\"")
			.contains("\"url_citation\": \"http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001\"")
			.contains("\"region_bucket\": \"SEOUL_GANGNAM_GU\"")
			.contains("\"topic\": \"policy_regulation\"")
			.contains("\"impact_target\": \"sale_price\"")
			.contains("\"impact_direction_hint\": \"unknown\"")
			.contains("\"signal_month\": \"2020-06\"")
			.doesNotContain("2020-07")
			.doesNotContain("본문")
			.doesNotContain("저장하면 안 되는 본문")
			.doesNotContain("다른 본문")
			.doesNotContain("content")
			.doesNotContain("body")
			.doesNotContain("full_text")
			.doesNotContain("summary");
		assertThat(markdown)
			.contains("1. 강남 재건축 규제 완화")
			.contains("source_key: `BIGKINDS_CSV:01200201.20200602123456001`")
			.contains("signal_month: `2020-06`")
			.doesNotContain("본문")
			.doesNotContain("저장하면 안 되는 본문")
			.doesNotContain("다른 본문");
		try (var walk = Files.walk(outputRoot)) {
			assertThat(walk
				.filter(path -> path.getFileName().toString().endsWith(".md"))
				.filter(path -> !path.toString().contains("_manifest"))
				.toList()).isEmpty();
		}
	}

	@Test
	@DisplayName("shortlist는 cp949 tab 18컬럼을 처리하고 URL 없는 6컬럼은 MISSING_URL_COLUMNS로 skip한다")
	void writesShortlistFromCp949TabAndSkipsSixColumnCsv(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Path outputRoot = tempDir.resolve("obsidian");
		Files.createDirectories(inputDir);
		NewsRuntimeProperties properties = properties();
		properties.getResearchSeed().setPeriodStart(java.time.LocalDate.of(2019, 10, 1));
		properties.getResearchSeed().setPeriodEnd(java.time.LocalDate.of(2019, 10, 31));
		BigKindsCsvResearchNoteGenerator shortlistGenerator = new BigKindsCsvResearchNoteGenerator(
			properties,
			Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)
		);
		String row = "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01500151.20191001060714001\t2019-10-01\t경남도민일보\t기자\t도내 미분양 줄었지만 누적 물량 해소 언제쯤\t경제>부동산\t\t\t\t\t\t\t경남\t국토교통부\t미분양,아파트,주택\t미분양,경남,아파트\t저장하면 안 되는 본문\thttps://example.com/unsold\n";
		Files.writeString(inputDir.resolve("cp949-tab.csv"), HEADER_18.replace(',', '\t') + "\n" + row, Charset.forName("MS949"));
		Files.writeString(inputDir.resolve("six-column.csv"), "일자,언론사,제목,키워드,특성추출,본문\n2021-04-01,신문사,강남 아파트 규제,강남,규제,본문\n", Charset.forName("MS949"));

		HistoricalNewsCsvShortlistWriteResult result = shortlistGenerator.writeShortlists(inputDir, outputRoot);

		assertThat(result.candidateCount()).isEqualTo(1);
		assertThat(result.skippedFileCount()).isEqualTo(1);
		assertThat(result.skippedByReason()).containsEntry("MISSING_URL_COLUMNS", 1);
		Path manifest;
		try (var walk = Files.walk(outputRoot)) {
			manifest = walk
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.filter(path -> path.toString().contains("_manifest"))
				.findFirst()
				.orElseThrow();
		}
		assertThat(Files.readString(manifest))
			.contains("\"skipped_by_reason\": {\"MISSING_URL_COLUMNS\":1}")
			.doesNotContain("본문")
			.doesNotContain("저장하면 안 되는 본문");
	}

	private NewsRuntimeProperties properties() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.getResearchSeed().setCsvInputDir("unused");
		properties.getResearchSeed().setCsvMaxNotesPerRun(100);
		properties.getResearchSeed().setCsvShortlistLimit(20);
		return properties;
	}
}
