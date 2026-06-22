package com.home.news.application;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.home.domain.news.NewsAvailabilityBasis;
import com.home.domain.news.NewsDiscoveryMethod;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.NewsSignalTopic;
import com.home.domain.news.NewsSource;
import com.home.domain.news.NewsVerificationStatus;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.news.NewsRuntimeProperties;
import com.home.news.support.TextDigests;

public class BigKindsCsvResearchNoteGenerator {

	private static final String SKIP_MISSING_URL_COLUMNS = "MISSING_URL_COLUMNS";
	private static final Pattern PROVIDER_RECORD_ID_PATTERN = Pattern.compile("[?&]newsId=([^&]+)");
	private static final DateTimeFormatter RUN_ID_TIME_FORMAT = DateTimeFormatter
		.ofPattern("yyyyMMddHHmmss")
		.withZone(java.time.ZoneOffset.UTC);
	private final NewsRuntimeProperties properties;
	private final Clock clock;

	public BigKindsCsvResearchNoteGenerator(NewsRuntimeProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public HistoricalNewsCsvNoteWriteResult writeNotes(Path inputDir, Path outputRoot) {
		if (!Files.exists(inputDir)) {
			throw new NewsCollectionException("BigKinds CSV input directory does not exist: " + inputDir);
		}
		List<Path> csvFiles = csvFiles(inputDir);
		Map<String, Integer> skippedByReason = new LinkedHashMap<>();
		int generated = 0;
		int skippedFiles = 0;
		int maxNotes = Math.max(0, properties.getResearchSeed().getCsvMaxNotesPerRun());
		for (Path csvFile : csvFiles) {
			CsvFile parsed = parseCsv(csvFile);
			if (!parsed.hasRequiredUrlColumns()) {
				skippedFiles++;
				increment(skippedByReason, SKIP_MISSING_URL_COLUMNS);
				continue;
			}
			for (CsvRow row : parsed.rows()) {
				if (generated >= maxNotes) {
					break;
				}
				Optional<BigKindsCsvCandidate> candidate = candidate(csvFile, row);
				if (candidate.isEmpty()) {
					continue;
				}
				writeNote(outputRoot, candidate.get());
				generated++;
			}
		}
		writeManifest(outputRoot, csvFiles.size(), generated, skippedFiles, skippedByReason);
		return new HistoricalNewsCsvNoteWriteResult(csvFiles.size(), generated, skippedFiles, Map.copyOf(skippedByReason), outputRoot);
	}

	public HistoricalNewsCsvShortlistWriteResult writeShortlists(Path inputDir, Path outputRoot) {
		if (!Files.exists(inputDir)) {
			throw new NewsCollectionException("BigKinds CSV input directory does not exist: " + inputDir);
		}
		List<Path> csvFiles = csvFiles(inputDir);
		Map<String, Integer> skippedByReason = new LinkedHashMap<>();
		List<BigKindsCsvCandidate> candidates = new ArrayList<>();
		int skippedFiles = 0;
		for (Path csvFile : csvFiles) {
			CsvFile parsed = parseCsv(csvFile);
			if (!parsed.hasRequiredUrlColumns()) {
				skippedFiles++;
				increment(skippedByReason, SKIP_MISSING_URL_COLUMNS);
				continue;
			}
			for (CsvRow row : parsed.rows()) {
				Optional<BigKindsCsvCandidate> candidate = candidate(csvFile, row);
				if (candidate.isEmpty() || !withinResearchPeriod(candidate.get().publishedDate())) {
					continue;
				}
				candidates.add(candidate.get());
			}
		}
		Map<YearMonth, List<BigKindsCsvCandidate>> byMonth = candidates.stream()
			.collect(Collectors.groupingBy(BigKindsCsvCandidate::signalMonth, LinkedHashMap::new, Collectors.toList()));
		int monthCount = 0;
		int shortlistedCount = 0;
		String runId = csvShortlistRunId(csvFiles.size(), candidates.size(), skippedFiles, skippedByReason);
		for (Map.Entry<YearMonth, List<BigKindsCsvCandidate>> entry : byMonth.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.toList()) {
			List<BigKindsCsvShortlistItem> items = shortlistItems(entry.getValue());
			if (items.isEmpty()) {
				continue;
			}
			writeShortlist(outputRoot, entry.getKey(), runId, csvFiles.size(), skippedFiles, skippedByReason, items);
			monthCount++;
			shortlistedCount += items.size();
		}
		return new HistoricalNewsCsvShortlistWriteResult(
			csvFiles.size(),
			monthCount,
			shortlistedCount,
			skippedFiles,
			Map.copyOf(skippedByReason),
			outputRoot
		);
	}

	private List<Path> csvFiles(Path inputDir) {
		try (var stream = Files.walk(inputDir)) {
			return stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
				.sorted(Comparator.comparing(Path::toString))
				.toList();
		}
		catch (IOException ex) {
			throw new NewsCollectionException("BigKinds CSV input scan failed: " + inputDir, ex);
		}
	}

	private CsvFile parseCsv(Path csvFile) {
		try {
			String text = decode(Files.readAllBytes(csvFile));
			char delimiter = delimiter(text);
			List<List<String>> records = parseRecords(stripBom(text), delimiter);
			if (records.isEmpty()) {
				return new CsvFile(Map.of(), List.of());
			}
			Map<String, Integer> header = header(records.get(0));
			List<CsvRow> rows = new ArrayList<>();
			for (int index = 1; index < records.size(); index++) {
				List<String> values = records.get(index);
				if (values.stream().allMatch(String::isBlank)) {
					continue;
				}
				rows.add(new CsvRow(index + 1, header, values));
			}
			return new CsvFile(header, rows);
		}
		catch (IOException ex) {
			throw new NewsCollectionException("BigKinds CSV read failed: " + csvFile, ex);
		}
	}

	private String decode(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException ex) {
			return Charset.forName("MS949").decode(ByteBuffer.wrap(bytes)).toString();
		}
	}

	private String stripBom(String text) {
		if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
			return text.substring(1);
		}
		return text;
	}

	private char delimiter(String text) {
		String firstLine = stripBom(text).lines().findFirst().orElse("");
		return count(firstLine, '\t') > count(firstLine, ',') ? '\t' : ',';
	}

	private int count(String value, char target) {
		int count = 0;
		boolean quoted = false;
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (ch == '"') {
				quoted = !quoted;
			}
			else if (!quoted && ch == target) {
				count++;
			}
		}
		return count;
	}

	private List<List<String>> parseRecords(String text, char delimiter) {
		List<List<String>> records = new ArrayList<>();
		List<String> record = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < text.length(); index++) {
			char ch = text.charAt(index);
			if (ch == '"') {
				if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
					field.append('"');
					index++;
				}
				else {
					quoted = !quoted;
				}
			}
			else if (!quoted && ch == delimiter) {
				record.add(field.toString());
				field.setLength(0);
			}
			else if (!quoted && (ch == '\n' || ch == '\r')) {
				if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
					index++;
				}
				record.add(field.toString());
				field.setLength(0);
				records.add(record);
				record = new ArrayList<>();
			}
			else {
				field.append(ch);
			}
		}
		if (field.length() > 0 || !record.isEmpty()) {
			record.add(field.toString());
			records.add(record);
		}
		return records;
	}

	private Map<String, Integer> header(List<String> columns) {
		Map<String, Integer> header = new LinkedHashMap<>();
		for (int index = 0; index < columns.size(); index++) {
			header.put(columns.get(index).strip(), index);
		}
		return header;
	}

	private Optional<BigKindsCsvCandidate> candidate(Path csvFile, CsvRow row) {
		String title = row.value("제목");
		String publisher = row.value("언론사");
		String publishedDateText = row.value("일자");
		String providerUrl = row.value("주소");
		String originalUrl = row.value("원본주소");
		String url = originalUrl.isBlank() ? providerUrl : originalUrl;
		if (title.isBlank() || publisher.isBlank() || publishedDateText.isBlank() || url.isBlank() || providerUrl.isBlank()) {
			return Optional.empty();
		}
		String categoryText = String.join(" ", row.value("통합 분류1"), row.value("통합 분류2"), row.value("통합 분류3"));
		String keywordText = row.value("키워드");
		String extractedTerms = row.value("특성추출");
		String regionEntities = row.value("개체명(지역)");
		String organizationEntities = row.value("개체명(기업기관)");
		String screeningText = String.join(" ", title, keywordText, extractedTerms, regionEntities);
		if (excludedArticle(title, screeningText)) {
			return Optional.empty();
		}
		Optional<TopicProfile> topicProfile = TopicProfile.match(screeningText);
		Optional<NewsRegionBucket> regionBucket = regionBucket(screeningText);
		boolean realEstateCategory = categoryText.contains("경제>부동산");
		if (!realEstateCategory && topicProfile.isEmpty() && regionBucket.isEmpty()) {
			return Optional.empty();
		}
		TopicProfile topic = topicProfile.orElse(TopicProfile.DEFAULT);
		NewsRegionBucket bucket = regionBucket.orElse(NewsRegionBucket.NATIONAL);
		LocalDate publishedDate = LocalDate.parse(publishedDateText.strip());
		YearMonth signalMonth = YearMonth.from(publishedDate);
		String providerRecordId = providerRecordId(providerUrl);
		String candidateHash = candidateHash(csvFile.getFileName().toString(), row.rowNumber(), publisher, title, publishedDate);
		return Optional.of(new BigKindsCsvCandidate(
			csvFile.getFileName().toString(),
			row.rowNumber(),
			title.strip(),
			publisher.strip(),
			publishedDate,
			canonicalUrl(url.strip()),
			providerUrl.strip(),
			bucket,
			topic.topic(),
			topic.impactTarget(),
			SignalImpactDirection.unknown,
			signalMonth,
			"0.800",
			candidateHash,
			sourceKey(csvFile.getFileName().toString(), row.rowNumber(), publisher, title, publishedDate, providerRecordId),
			providerRecordId,
			originalUrl.strip(),
			keywordText.strip(),
			extractedTerms.strip(),
			regionEntities.strip(),
			organizationEntities.strip(),
			realEstateCategory,
			topicProfile.isPresent(),
			regionBucket.isPresent(),
			directHousingSignal(screeningText),
			selectionReason(realEstateCategory, topicProfile.isPresent(), regionBucket.isPresent(), directHousingSignal(screeningText))
		));
	}

	private boolean withinResearchPeriod(LocalDate date) {
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		return !date.isBefore(seed.getPeriodStart()) && !date.isAfter(seed.getPeriodEnd());
	}

	private boolean excludedArticle(String title, String screeningText) {
		String text = String.join(" ", title, screeningText);
		return text.contains("부음")
			|| text.contains("부고")
			|| text.contains("인사]")
			|| text.contains("[인사")
			|| text.contains("임원 인사")
			|| text.contains("기업공시");
	}

	private boolean directHousingSignal(String text) {
		return List.of("아파트", "주택", "재건축", "재개발", "분양", "전세", "실거래", "거래량", "미분양", "입주").stream()
			.anyMatch(text::contains);
	}

	private String selectionReason(boolean realEstateCategory, boolean topicMatched, boolean regionMatched, boolean directHousingSignal) {
		List<String> reasons = new ArrayList<>();
		if (realEstateCategory) {
			reasons.add("경제>부동산");
		}
		if (regionMatched) {
			reasons.add("지역 명확");
		}
		if (topicMatched) {
			reasons.add("topic 명확");
		}
		if (directHousingSignal) {
			reasons.add("아파트/주택시장 직접 신호");
		}
		if (reasons.isEmpty()) {
			return "부동산 관련 metadata match";
		}
		return String.join(", ", reasons);
	}

	private Optional<NewsRegionBucket> regionBucket(String text) {
		String compact = text == null ? "" : text;
		for (RegionAlias alias : RegionAlias.ALL) {
			if (alias.matches(compact)) {
				return Optional.of(alias.bucket());
			}
		}
		return Optional.empty();
	}

	private String providerRecordId(String providerUrl) {
		Matcher matcher = PROVIDER_RECORD_ID_PATTERN.matcher(providerUrl);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "";
	}

	private String canonicalUrl(String url) {
		try {
			URI uri = URI.create(url);
			if (uri.getScheme() == null) {
				return url;
			}
			return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
		}
		catch (Exception ex) {
			return url;
		}
	}

	private String candidateHash(String sourceFile, int rowNumber, String publisher, String title, LocalDate publishedDate) {
		return TextDigests.sha256Hex(String.join("|", sourceFile, String.valueOf(rowNumber), publisher, title, publishedDate.toString()))
			.substring(0, 16);
	}

	private String sourceKey(
		String sourceFile,
		int rowNumber,
		String publisher,
		String title,
		LocalDate publishedDate,
		String providerRecordId
	) {
		if (providerRecordId != null && !providerRecordId.isBlank()) {
			return NewsSource.BIGKINDS_CSV.name() + ":" + providerRecordId.strip();
		}
		return NewsSource.BIGKINDS_CSV.name() + ":" + TextDigests.sha256Hex(String.join("|",
			sourceFile,
			String.valueOf(rowNumber),
			publisher,
			title,
			publishedDate.toString()
		));
	}

	private String csvShortlistRunId(
		int fileCount,
		int candidateCount,
		int skippedFiles,
		Map<String, Integer> skippedByReason
	) {
		NewsRuntimeProperties.ResearchSeed seed = properties.getResearchSeed();
		String source = String.join("|",
			String.valueOf(fileCount),
			String.valueOf(candidateCount),
			String.valueOf(skippedFiles),
			skippedByReason.toString(),
			seed.getPeriodStart().toString(),
			seed.getPeriodEnd().toString(),
			String.valueOf(seed.getCsvShortlistLimit())
		);
		return RUN_ID_TIME_FORMAT.format(java.time.Instant.now(clock)) + "-" + TextDigests.sha256Hex(source).substring(0, 8);
	}

	private List<BigKindsCsvShortlistItem> shortlistItems(List<BigKindsCsvCandidate> candidates) {
		int limit = Math.max(0, properties.getResearchSeed().getCsvShortlistLimit());
		List<BigKindsCsvCandidate> ranked = candidates.stream()
			.sorted(Comparator
				.comparingInt(this::rankingScore)
				.reversed()
				.thenComparing(BigKindsCsvCandidate::publishedDate)
				.thenComparing(BigKindsCsvCandidate::sourceFile)
				.thenComparingInt(BigKindsCsvCandidate::sourceRowNumber))
			.limit(limit)
			.toList();
		List<BigKindsCsvShortlistItem> items = new ArrayList<>();
		for (int index = 0; index < ranked.size(); index++) {
			items.add(new BigKindsCsvShortlistItem(index + 1, ranked.get(index)));
		}
		return List.copyOf(items);
	}

	private int rankingScore(BigKindsCsvCandidate candidate) {
		int score = 0;
		if (!candidate.url().isBlank()) {
			score += 100;
		}
		if (candidate.realEstateCategory()) {
			score += 40;
		}
		if (candidate.regionMatched()) {
			score += 25;
		}
		if (candidate.topicMatched()) {
			score += 20;
		}
		if (candidate.directHousingSignal()) {
			score += 15;
		}
		if (!candidate.providerRecordId().isBlank()) {
			score += 5;
		}
		return score;
	}

	private void writeNote(Path outputRoot, BigKindsCsvCandidate candidate) {
		Path notePath = outputRoot
			.resolve("news-research-seed")
			.resolve(candidate.regionBucket().name())
			.resolve(String.valueOf(candidate.publishedDate().getYear()))
			.resolve(candidate.signalMonth().toString())
			.resolve(candidate.publishedDate() + "-" + candidate.candidateHash() + ".md");
		try {
			Files.createDirectories(notePath.getParent());
			Files.writeString(notePath, note(candidate));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("BigKinds CSV note write failed: " + notePath, ex);
		}
	}

	private String note(BigKindsCsvCandidate candidate) {
		return """
			---
			verification_status: %s
			source: %s
			discovery_method: %s
			availability_basis: %s
			model_dataset_tier: %s
			title: %s
			publisher: %s
			published_date: %s
			url: %s
			url_citation: %s
			region_bucket: %s
			topic: %s
			impact_target: %s
			impact_direction_hint: %s
			signal_month: %s
			confidence: %s
			screening_reasons: []
			candidate_hash: %s
			reviewed_at:
			reviewed_by:
			review_decision_reason:
			source_file: %s
			source_row_number: %d
			provider_record_id: %s
			original_url: %s
			---
			## 검수 참고
			- 키워드: %s
			- 특성추출: %s
			- 지역 개체: %s
			- 기관 개체: %s

			- [ ] URL 접속 가능
			- [ ] 기사 날짜가 signal_month 판단에 적합
			- [ ] 언론사/제목 일치
			- [ ] 지역 영향 직접적
			- [ ] topic/impact target 수정 필요 없음
			- [ ] 가격/전세/거래량/공급/risk 방향성이 설명 가능
			""".formatted(
			NewsVerificationStatus.NEEDS_REVIEW.name(),
			NewsSource.BIGKINDS_CSV.name(),
			NewsDiscoveryMethod.PROVIDER_EXPORT.name(),
			NewsAvailabilityBasis.LICENSED_HISTORICAL_EXPORT.name(),
			NewsModelDatasetTier.EXPERIMENTAL_SEED.name(),
			frontMatterScalar(candidate.title()),
			frontMatterScalar(candidate.publisher()),
			candidate.publishedDate(),
			frontMatterScalar(candidate.url()),
			frontMatterScalar(candidate.urlCitation()),
			candidate.regionBucket().name(),
			candidate.topic().name(),
			candidate.impactTarget().name(),
			candidate.impactDirectionHint().name(),
			candidate.signalMonth(),
			candidate.confidence(),
			candidate.candidateHash(),
			frontMatterScalar(candidate.sourceFile()),
			candidate.sourceRowNumber(),
			frontMatterScalar(candidate.providerRecordId()),
			frontMatterScalar(candidate.originalUrl()),
			bodyScalar(candidate.keywords()),
			bodyScalar(candidate.extractedTerms()),
			bodyScalar(candidate.regionEntities()),
			bodyScalar(candidate.organizationEntities())
		);
	}

	private void writeShortlist(
		Path outputRoot,
		YearMonth signalMonth,
		String runId,
		int fileCount,
		int skippedFiles,
		Map<String, Integer> skippedByReason,
		List<BigKindsCsvShortlistItem> items
	) {
		Path manifestRoot = outputRoot.resolve("news-research-seed").resolve("_manifest");
		String filePrefix = "csv-shortlist-" + signalMonth + "-" + runId;
		try {
			Files.createDirectories(manifestRoot);
			Files.writeString(manifestRoot.resolve(filePrefix + ".json"), shortlistJson(signalMonth, runId, fileCount, skippedFiles, skippedByReason, items));
			Files.writeString(manifestRoot.resolve(filePrefix + ".md"), shortlistMarkdown(signalMonth, runId, fileCount, skippedFiles, skippedByReason, items));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("BigKinds CSV shortlist write failed: " + manifestRoot, ex);
		}
	}

	private String shortlistJson(
		YearMonth signalMonth,
		String runId,
		int fileCount,
		int skippedFiles,
		Map<String, Integer> skippedByReason,
		List<BigKindsCsvShortlistItem> items
	) {
		return """
			{
			  "run_id": "%s",
			  "source": "BIGKINDS_CSV",
			  "mode": "GENERATE_CSV_SHORTLIST",
			  "signal_month": "%s",
			  "period_start": "%s",
			  "period_end": "%s",
			  "files": %d,
			  "shortlist_limit": %d,
			  "shortlist_count": %d,
			  "skipped_files": %d,
			  "skipped_by_reason": %s,
			  "items": [
			%s
			  ]
			}
			""".formatted(
			jsonEscape(runId),
			signalMonth,
			properties.getResearchSeed().getPeriodStart(),
			properties.getResearchSeed().getPeriodEnd(),
			fileCount,
			Math.max(0, properties.getResearchSeed().getCsvShortlistLimit()),
			items.size(),
			skippedFiles,
			jsonMap(skippedByReason),
			items.stream().map(this::shortlistItemJson).collect(Collectors.joining(",\n"))
		);
	}

	private String shortlistItemJson(BigKindsCsvShortlistItem item) {
		BigKindsCsvCandidate candidate = item.candidate();
		return """
			    {
			      "number": %d,
			      "source_key": "%s",
			      "candidate_hash": "%s",
			      "source_file": "%s",
			      "source_row_number": %d,
			      "provider_record_id": "%s",
			      "title": "%s",
			      "publisher": "%s",
			      "published_date": "%s",
			      "url": "%s",
			      "url_citation": "%s",
			      "region_bucket": "%s",
			      "topic": "%s",
			      "impact_target": "%s",
			      "impact_direction_hint": "%s",
			      "signal_month": "%s",
			      "selection_reason": "%s"
			    }""".formatted(
			item.number(),
			jsonEscape(candidate.sourceKey()),
			jsonEscape(candidate.candidateHash()),
			jsonEscape(candidate.sourceFile()),
			candidate.sourceRowNumber(),
			jsonEscape(candidate.providerRecordId()),
			jsonEscape(candidate.title()),
			jsonEscape(candidate.publisher()),
			candidate.publishedDate(),
			jsonEscape(candidate.url()),
			jsonEscape(candidate.urlCitation()),
			candidate.regionBucket().name(),
			candidate.topic().name(),
			candidate.impactTarget().name(),
			candidate.impactDirectionHint().name(),
			candidate.signalMonth(),
			jsonEscape(candidate.selectionReason())
		);
	}

	private String shortlistMarkdown(
		YearMonth signalMonth,
		String runId,
		int fileCount,
		int skippedFiles,
		Map<String, Integer> skippedByReason,
		List<BigKindsCsvShortlistItem> items
	) {
		StringBuilder builder = new StringBuilder();
		builder.append("---\n");
		builder.append("run_id: ").append(runId).append('\n');
		builder.append("source: BIGKINDS_CSV\n");
		builder.append("mode: GENERATE_CSV_SHORTLIST\n");
		builder.append("signal_month: ").append(signalMonth).append('\n');
		builder.append("period_start: ").append(properties.getResearchSeed().getPeriodStart()).append('\n');
		builder.append("period_end: ").append(properties.getResearchSeed().getPeriodEnd()).append('\n');
		builder.append("files: ").append(fileCount).append('\n');
		builder.append("shortlist_limit: ").append(Math.max(0, properties.getResearchSeed().getCsvShortlistLimit())).append('\n');
		builder.append("shortlist_count: ").append(items.size()).append('\n');
		builder.append("skipped_files: ").append(skippedFiles).append('\n');
		builder.append("skipped_by_reason: ").append(skippedByReason).append('\n');
		builder.append("---\n\n");
		builder.append("# BigKinds CSV shortlist ").append(signalMonth).append("\n\n");
		for (BigKindsCsvShortlistItem item : items) {
			BigKindsCsvCandidate candidate = item.candidate();
			builder.append(item.number()).append(". ").append(markdownLine(candidate.title())).append('\n');
			builder.append("   - source_key: `").append(candidate.sourceKey()).append("`\n");
			builder.append("   - candidate_hash: `").append(candidate.candidateHash()).append("`\n");
			builder.append("   - publisher/date: ").append(markdownLine(candidate.publisher())).append(" / `").append(candidate.publishedDate()).append("`\n");
			builder.append("   - url: ").append(markdownLine(candidate.url())).append('\n');
			builder.append("   - url_citation: ").append(markdownLine(candidate.urlCitation())).append('\n');
			builder.append("   - region/topic/target: `").append(candidate.regionBucket().name()).append("` / `")
				.append(candidate.topic().name()).append("` / `").append(candidate.impactTarget().name()).append("`\n");
			builder.append("   - impact_direction_hint: `").append(candidate.impactDirectionHint().name()).append("`\n");
			builder.append("   - signal_month: `").append(candidate.signalMonth()).append("`\n");
			builder.append("   - source_file/row: `").append(markdownLine(candidate.sourceFile())).append("` / `")
				.append(candidate.sourceRowNumber()).append("`\n");
			builder.append("   - provider_record_id: `").append(markdownLine(candidate.providerRecordId())).append("`\n");
			builder.append("   - selection_reason: ").append(markdownLine(candidate.selectionReason())).append("\n\n");
		}
		return builder.toString();
	}

	private void writeManifest(Path outputRoot, int fileCount, int generated, int skippedFiles, Map<String, Integer> skippedByReason) {
		Path manifestRoot = outputRoot.resolve("news-research-seed").resolve("_manifest");
		String runId = "bigkinds-csv-" + RUN_ID_TIME_FORMAT.format(java.time.Instant.now(clock)) + "-"
			+ TextDigests.sha256Hex(fileCount + "|" + generated + "|" + skippedFiles + "|" + skippedByReason).substring(0, 8);
		try {
			Files.createDirectories(manifestRoot);
			Files.writeString(manifestRoot.resolve(runId + ".json"), """
				{
				  "run_id": "%s",
				  "source": "BIGKINDS_CSV",
				  "files": %d,
				  "notes": %d,
				  "skipped_files": %d,
				  "skipped_by_reason": %s
				}
				""".formatted(runId, fileCount, generated, skippedFiles, jsonMap(skippedByReason)));
			Files.writeString(manifestRoot.resolve(runId + ".md"), """
				---
				run_id: %s
				source: BIGKINDS_CSV
				---
				files: %d
				notes: %d
				skipped_files: %d
				skipped_by_reason: %s
				""".formatted(runId, fileCount, generated, skippedFiles, skippedByReason));
		}
		catch (IOException ex) {
			throw new NewsCollectionException("BigKinds CSV manifest write failed: " + manifestRoot, ex);
		}
	}

	private String jsonMap(Map<String, Integer> values) {
		return values.entrySet().stream()
			.map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
			.collect(Collectors.joining(",", "{", "}"));
	}

	private String jsonEscape(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder escaped = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			switch (ch) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\b' -> escaped.append("\\b");
				case '\f' -> escaped.append("\\f");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (ch < 0x20) {
						escaped.append(String.format("\\u%04x", (int) ch));
					}
					else {
						escaped.append(ch);
					}
				}
			}
		}
		return escaped.toString();
	}

	private void increment(Map<String, Integer> values, String key) {
		values.put(key, values.getOrDefault(key, 0) + 1);
	}

	private String frontMatterScalar(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String sanitized = value.replace("\r", " ").replace("\n", " ").replace("---", "- - -").strip();
		if (sanitized.contains(":") || sanitized.contains("#") || sanitized.contains("[") || sanitized.contains("]") || sanitized.contains("\"")) {
			return "\"" + sanitized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		return sanitized;
	}

	private String bodyScalar(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.replace("\r", " ").replace("\n", " ").replace("---", "- - -").strip();
	}

	private String markdownLine(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.replace("\r", " ").replace("\n", " ").replace("`", "'").strip();
	}

	private record CsvFile(Map<String, Integer> header, List<CsvRow> rows) {

		boolean hasRequiredUrlColumns() {
			return header.size() >= 18
				&& header.containsKey("주소")
				&& header.containsKey("일자")
				&& header.containsKey("언론사")
				&& header.containsKey("제목")
				&& header.containsKey("원본주소");
		}
	}

	private record CsvRow(int rowNumber, Map<String, Integer> header, List<String> values) {

		String value(String column) {
			Integer index = header.get(column);
			if (index == null || index >= values.size()) {
				return "";
			}
			return values.get(index);
		}
	}

	private record BigKindsCsvCandidate(
		String sourceFile,
		int sourceRowNumber,
		String title,
		String publisher,
		LocalDate publishedDate,
		String url,
		String urlCitation,
		NewsRegionBucket regionBucket,
		NewsSignalTopic topic,
		SignalImpactTarget impactTarget,
		SignalImpactDirection impactDirectionHint,
		YearMonth signalMonth,
		String confidence,
		String candidateHash,
		String sourceKey,
		String providerRecordId,
		String originalUrl,
		String keywords,
		String extractedTerms,
		String regionEntities,
		String organizationEntities,
		boolean realEstateCategory,
		boolean topicMatched,
		boolean regionMatched,
		boolean directHousingSignal,
		String selectionReason
	) {
	}

	private record BigKindsCsvShortlistItem(
		int number,
		BigKindsCsvCandidate candidate
	) {
	}

	private record TopicProfile(
		NewsSignalTopic topic,
		List<String> keywords,
		SignalImpactTarget impactTarget
	) {

		static final TopicProfile DEFAULT = new TopicProfile(NewsSignalTopic.development_project, List.of("부동산"), SignalImpactTarget.sale_price);
		static final List<TopicProfile> ALL = List.of(
			new TopicProfile(NewsSignalTopic.policy_regulation, List.of("부동산 정책", "규제지역", "토지거래허가구역", "분양가상한제", "규제"), SignalImpactTarget.sale_price),
			new TopicProfile(NewsSignalTopic.tax, List.of("보유세", "양도세", "취득세", "종부세"), SignalImpactTarget.sale_price),
			new TopicProfile(NewsSignalTopic.loan_rate, List.of("대출 규제", "DSR", "LTV", "금리", "주담대", "전세대출"), SignalImpactTarget.liquidity),
			new TopicProfile(NewsSignalTopic.subscription, List.of("청약", "분양", "특별공급", "무순위"), SignalImpactTarget.supply),
			new TopicProfile(NewsSignalTopic.reconstruction_redevelopment, List.of("재건축", "재개발", "정비구역", "안전진단", "조합", "사업시행인가"), SignalImpactTarget.sale_price),
			new TopicProfile(NewsSignalTopic.supply, List.of("공급", "입주", "입주물량", "착공", "분양물량"), SignalImpactTarget.supply),
			new TopicProfile(NewsSignalTopic.transport_infra, List.of("GTX", "지하철", "철도", "역세권", "노선", "개통", "착공"), SignalImpactTarget.sale_price),
			new TopicProfile(NewsSignalTopic.school_district, List.of("학군", "학교 배정", "교육청", "자사고", "특목고"), SignalImpactTarget.jeonse_price),
			new TopicProfile(NewsSignalTopic.jeonse_rent, List.of("전세", "월세", "임대차", "전세난", "역전세"), SignalImpactTarget.jeonse_price),
			new TopicProfile(NewsSignalTopic.transaction_volume, List.of("거래량", "매매거래", "실거래", "거래절벽"), SignalImpactTarget.volume),
			new TopicProfile(NewsSignalTopic.auction_distress, List.of("경매", "공매", "부실", "연체", "미상환"), SignalImpactTarget.risk),
			new TopicProfile(NewsSignalTopic.unsold_inventory, List.of("미분양", "준공후 미분양", "분양률"), SignalImpactTarget.supply),
			new TopicProfile(NewsSignalTopic.development_project, List.of("개발사업", "복합개발", "신도시", "공공주택지구", "지구지정"), SignalImpactTarget.sale_price),
			new TopicProfile(NewsSignalTopic.macro_rate, List.of("기준금리", "한국은행", "채권금리", "가계대출"), SignalImpactTarget.liquidity)
		);

		static Optional<TopicProfile> match(String text) {
			return ALL.stream()
				.filter(profile -> profile.keywords().stream().anyMatch(text::contains))
				.findFirst();
		}
	}

	private record RegionAlias(NewsRegionBucket bucket, List<String> aliases) {

		static final List<RegionAlias> ALL = List.of(
			new RegionAlias(NewsRegionBucket.SEOUL_GANGNAM_GU, List.of("강남구", "강남", "대치", "개포", "압구정", "삼성", "역삼", "도곡", "수서")),
			new RegionAlias(NewsRegionBucket.SEOUL_SEOCHO_GU, List.of("서초구", "서초", "반포", "잠원", "방배", "양재")),
			new RegionAlias(NewsRegionBucket.SEOUL_SONGPA_GU, List.of("송파구", "송파", "잠실", "가락", "문정", "위례")),
			new RegionAlias(NewsRegionBucket.SEOUL_YONGSAN_GU, List.of("용산구", "용산", "한남", "이촌", "서빙고", "원효로")),
			new RegionAlias(NewsRegionBucket.SEOUL_MAPO_GU, List.of("마포구", "마포", "공덕", "아현", "상암", "합정")),
			new RegionAlias(NewsRegionBucket.SEOUL_SEONGDONG_GU, List.of("성동구", "성수", "왕십리", "옥수", "금호", "행당")),
			new RegionAlias(NewsRegionBucket.SEOUL_YEONGDEUNGPO_GU, List.of("영등포구", "여의도", "문래", "신길", "당산")),
			new RegionAlias(NewsRegionBucket.SEOUL_YANGCHEON_GU, List.of("양천구", "목동", "신정", "신월")),
			new RegionAlias(NewsRegionBucket.SEOUL_NOWON_GU, List.of("노원구", "상계", "중계", "하계", "월계")),
			new RegionAlias(NewsRegionBucket.SEOUL_GANGDONG_GU, List.of("강동구", "둔촌", "고덕", "명일", "상일", "암사")),
			new RegionAlias(NewsRegionBucket.SEOUL, List.of("서울", "강남권", "한강변", "서울 아파트")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_SEONGNAM_SI, List.of("성남", "분당", "판교", "수정구", "중원구")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_GWACHEON_SI, List.of("과천", "과천지식정보타운", "정부과천청사")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_HANAM_SI, List.of("하남", "미사", "감일", "교산")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_GWANGMYEONG_SI, List.of("광명", "철산", "하안", "소하", "광명뉴타운")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_GOYANG_SI, List.of("고양", "일산", "덕양", "킨텍스", "대곡")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_YONGIN_SI, List.of("용인", "수지", "기흥", "처인", "동백")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_SUWON_SI, List.of("수원", "광교", "영통", "권선", "장안", "팔달")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_HWASEONG_SI, List.of("화성", "동탄", "봉담", "병점", "향남")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_NAMYANGJU_SI, List.of("남양주", "다산", "별내", "왕숙", "평내")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_GIMPO_SI, List.of("김포", "한강신도시", "장기", "걸포", "풍무")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_ANYANG_SI, List.of("안양", "평촌", "동안구", "만안구", "인덕원")),
			new RegionAlias(NewsRegionBucket.GYEONGGI_UIWANG_SI, List.of("의왕", "백운", "오전", "내손")),
			new RegionAlias(NewsRegionBucket.GYEONGGI, List.of("경기", "수도권", "경기도", "경기 아파트"))
		);

		boolean matches(String text) {
			return aliases.stream().anyMatch(text::contains);
		}
	}
}
