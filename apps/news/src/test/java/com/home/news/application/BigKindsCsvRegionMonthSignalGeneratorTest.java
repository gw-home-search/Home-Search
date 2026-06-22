package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsRegionBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BigKindsCsvRegionMonthSignalGeneratorTest {

	private static final String HEADER = "주소,일자,언론사,기고자,제목,통합 분류1,통합 분류2,통합 분류3,사건_사고 분류1,사건_사고 분류2,사건_사고 분류3,개체명(인물),개체명(지역),개체명(기업기관),키워드,특성추출,본문,원본주소";
	private final BigKindsCsvRegionMonthSignalGenerator generator = new BigKindsCsvRegionMonthSignalGenerator(new RegionAliasMatcher());

	@Test
	@DisplayName("BigKinds CSV aggregate는 월수 * 26 bucket rows를 만들고 article note를 생성하지 않는다")
	void createsMonthBucketRowsWithoutArticleNotes(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Path outputPath = tempDir.resolve("local-input").resolve("region-month-signal-bigkinds.csv.jsonl");
		Files.createDirectories(inputDir);
		Files.writeString(inputDir.resolve("sample.csv"), "\uFEFF" + HEADER + "\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200602123456001,2020-06-02,경인일보,기자,\"강남 재건축 규제 완화\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"재건축,강남,아파트\",\"재건축,규제완화,강남\",저장 금지 원고,https://example.com/article\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20200702123456001,2020-07-02,경인일보,기자,\"성수동 아파트 교통 호재\",경제>부동산,,,,,,,\"서울, 성동구\",\"서울시\",\"성수동,아파트\",\"교통,성수동\",저장 금지 원고,https://example.com/july\n");

		var snapshots = generator.generate(inputDir, "test-method-v1");
		new RegionMonthSignalJsonl(new ObjectMapper(), new RegionMonthSignalValidator()).write(outputPath, snapshots);

		assertThat(snapshots).hasSize(2 * NewsRegionBucket.values().length);
		assertThat(snapshots)
			.filteredOn(snapshot -> snapshot.regionBucket() == NewsRegionBucket.SEOUL_GANGNAM_GU)
			.anySatisfy(snapshot -> assertThat(snapshot.directEvidenceCount()).isEqualTo(1));
		assertThat(Files.readString(outputPath))
			.contains("\"region_bucket\":\"SEOUL_GANGNAM_GU\"")
			.doesNotContain("저장 금지 원고")
			.doesNotContain("content")
			.doesNotContain("full_text")
			.doesNotContain("article_summary");
		try (var walk = Files.walk(tempDir)) {
			assertThat(walk.filter(path -> path.getFileName().toString().endsWith(".md")).toList()).isEmpty();
		}
	}

	@Test
	@DisplayName("BigKinds CSV aggregate는 bucket별 metadata evidence를 최대 10개까지 담는다")
	void keepsUpToTenCsvEvidenceLinks(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Files.createDirectories(inputDir);
		StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\n");
		for (int i = 1; i <= 12; i++) {
			csv.append("http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.202006")
				.append(String.format("%02d", i))
				.append("123456001,2020-06-")
				.append(String.format("%02d", i))
				.append(",경인일보,기자,\"강남 재건축 규제 완화 ")
				.append(i)
				.append("\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"재건축,강남,아파트\",\"재건축,규제완화,강남\",저장 금지 원고,https://example.com/article")
				.append(i)
				.append("\n");
		}
		Files.writeString(inputDir.resolve("부동산 (2020.06.01-2020.06.30).csv"), csv);

		var gangnam = generator.generate(inputDir, "test-method-v1").stream()
			.filter(snapshot -> snapshot.regionBucket() == NewsRegionBucket.SEOUL_GANGNAM_GU)
			.findFirst()
			.orElseThrow();

		assertThat(gangnam.directEvidenceCount()).isEqualTo(10);
		assertThat(gangnam.evidence()).hasSize(10);
	}

	@Test
	@DisplayName("BigKinds CSV confidence는 evidence 개수 고정값이 아니라 metadata 품질과 matched 규모를 반영한다")
	void confidenceReflectsMetadataQualityAndMatchedVolume(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Files.createDirectories(inputDir);
		StringBuilder csv = new StringBuilder("\uFEFF").append(HEADER).append("\n");
		for (int i = 1; i <= 10; i++) {
			csv.append(csvRow(
				LocalDate.of(2020, 6, i),
				"경제일보" + i,
				"강남구 아파트 매매가격 0." + i + "% 상승 재건축 규제 완화",
				"서울, 강남구",
				"국토교통부",
				"강남구,아파트,매매가격,상승,재건축,규제완화",
				"강남구,아파트,매매가격,상승,재건축,규제완화",
				"https://example.com/high-" + i
			));
			csv.append(csvRow(
				LocalDate.of(2020, 6, i + 10),
				"지역일보",
				"의왕시 생활 정보 알림",
				"경기, 의왕시",
				"의왕시",
				"의왕시,생활,정보",
				"생활,정보",
				"https://example.com/low-" + i
			));
		}
		Files.writeString(inputDir.resolve("부동산 (2020.06.01-2020.06.30).csv"), csv);

		var snapshots = generator.generate(inputDir, "test-method-v1");
		var gangnam = snapshots.stream()
			.filter(snapshot -> snapshot.regionBucket() == NewsRegionBucket.SEOUL_GANGNAM_GU)
			.findFirst()
			.orElseThrow();
		var uiwang = snapshots.stream()
			.filter(snapshot -> snapshot.regionBucket() == NewsRegionBucket.GYEONGGI_UIWANG_SI)
			.findFirst()
			.orElseThrow();

		assertThat(gangnam.directEvidenceCount()).isEqualTo(10);
		assertThat(uiwang.directEvidenceCount()).isEqualTo(10);
		assertThat(gangnam.confidence()).isGreaterThan(uiwang.confidence());
		assertThat(gangnam.confidence()).isGreaterThanOrEqualTo(new BigDecimal("0.800"));
		assertThat(uiwang.confidence()).isLessThanOrEqualTo(new BigDecimal("0.650"));
	}

	@Test
	@DisplayName("CP949 BigKinds CSV도 header token 기준으로 decoding해서 aggregate에 포함한다")
	void decodesCp949CsvByHeaderTokens(@TempDir Path tempDir) throws Exception {
		Path inputDir = tempDir.resolve("input");
		Files.createDirectories(inputDir);
		Files.writeString(inputDir.resolve("부동산 (2017.01.01-2017.01.31).csv"), HEADER + "\n"
			+ "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201.20170102123456001,2017-01-02,경인일보,기자,\"강남 재건축 규제 완화\",경제>부동산,,,,,,,\"서울, 강남구\",\"국토교통부\",\"재건축,강남,아파트\",\"재건축,규제완화,강남\",저장 금지 원고,https://example.com/article\n", Charset.forName("MS949"));

		var national = generator.generate(inputDir, "test-method-v1").stream()
			.filter(snapshot -> snapshot.regionBucket() == NewsRegionBucket.NATIONAL)
			.findFirst()
			.orElseThrow();

		assertThat(national.newsCount()).isEqualTo(1);
	}

	private static String csvRow(
		LocalDate publishedDate,
		String publisher,
		String title,
		String regionEntities,
		String organizationEntities,
		String keywords,
		String features,
		String originalUrl
	) {
		String newsId = originalUrl.substring(originalUrl.lastIndexOf('-') + 1);
		return "http://www.bigkinds.or.kr/news/newsDetailView.do?newsId=01200201."
			+ publishedDate.toString().replace("-", "")
			+ newsId
			+ ","
			+ publishedDate
			+ ","
			+ publisher
			+ ",기자,\""
			+ title
			+ "\",경제>부동산,,,,,,,\""
			+ regionEntities
			+ "\",\""
			+ organizationEntities
			+ "\",\""
			+ keywords
			+ "\",\""
			+ features
			+ "\",저장 금지 원고,"
			+ originalUrl
			+ "\n";
	}
}
