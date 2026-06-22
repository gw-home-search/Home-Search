package com.home.news.application;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalEvidenceScope;
import com.home.domain.news.RegionMonthSignalSourceKind;

public class BigKindsCsvRegionMonthSignalGenerator {

	public static final int CSV_EVIDENCE_LIMIT = 10;
	private static final Pattern NEWS_ID_PATTERN = Pattern.compile("newsId=([^&]+)");
	private static final Pattern FILE_RANGE_PATTERN = Pattern.compile("(\\d{4})\\.(\\d{2})\\.\\d{2}-(\\d{4})\\.(\\d{2})\\.\\d{2}");
	private static final Pattern NUMERIC_SIGNAL_PATTERN = Pattern.compile("\\d+(\\.\\d+)?\\s*(%|억|만원|가구|세대|호|층|㎡|평)");
	private final RegionAliasMatcher regionAliasMatcher;

	public BigKindsCsvRegionMonthSignalGenerator(RegionAliasMatcher regionAliasMatcher) {
		this.regionAliasMatcher = regionAliasMatcher;
	}

	public List<RegionMonthSignalSnapshot> generate(Path csvInputDir, String methodVersion) {
		List<BigKindsRow> rows = readRows(csvInputDir);
		List<YearMonth> months = coveredMonths(csvInputDir, rows);
		if (months.isEmpty()) {
			return List.of();
		}
		Map<YearMonth, List<BigKindsRow>> rowsByMonth = new HashMap<>();
		for (BigKindsRow row : rows) {
			rowsByMonth.computeIfAbsent(YearMonth.from(row.publishedDate()), ignored -> new ArrayList<>()).add(row);
		}
		List<RegionMonthSignalSnapshot> snapshots = new ArrayList<>();
		for (YearMonth month : months) {
			List<BigKindsRow> monthRows = rowsByMonth.getOrDefault(month, List.of());
			Map<NewsRegionBucket, List<BigKindsRow>> matched = matchByBucket(monthRows);
			for (NewsRegionBucket bucket : NewsRegionBucket.values()) {
				List<BigKindsRow> bucketRows = matched.getOrDefault(bucket, List.of());
				snapshots.add(toSnapshot(bucket, month, methodVersion, monthRows.size(), bucketRows));
			}
		}
		return List.copyOf(snapshots);
	}

	private List<YearMonth> coveredMonths(Path csvInputDir, List<BigKindsRow> rows) {
		TreeSet<YearMonth> months = new TreeSet<>();
		if (Files.exists(csvInputDir)) {
			try (var paths = Files.walk(csvInputDir)) {
				for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".csv")).toList()) {
					Matcher matcher = FILE_RANGE_PATTERN.matcher(path.getFileName().toString());
					if (matcher.find()) {
						YearMonth start = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
						YearMonth end = YearMonth.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
						for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
							months.add(month);
						}
					}
				}
			}
			catch (IOException ex) {
				throw new NewsSignalValidationException("failed to inspect BigKinds CSV file ranges: " + csvInputDir, ex);
			}
		}
		if (months.isEmpty() && !rows.isEmpty()) {
			YearMonth start = rows.stream().map(row -> YearMonth.from(row.publishedDate())).min(Comparator.naturalOrder()).orElseThrow();
			YearMonth end = rows.stream().map(row -> YearMonth.from(row.publishedDate())).max(Comparator.naturalOrder()).orElseThrow();
			for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
				months.add(month);
			}
		}
		return List.copyOf(months);
	}

	private Map<NewsRegionBucket, List<BigKindsRow>> matchByBucket(List<BigKindsRow> monthRows) {
		Map<NewsRegionBucket, List<BigKindsRow>> matched = new EnumMap<>(NewsRegionBucket.class);
		for (BigKindsRow row : monthRows) {
			Set<NewsRegionBucket> buckets = regionAliasMatcher.match(row.searchText());
			if (buckets.isEmpty()) {
				buckets = Set.of(NewsRegionBucket.OTHER);
			}
			for (NewsRegionBucket bucket : buckets) {
				matched.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(row);
			}
		}
		return matched;
	}

	private RegionMonthSignalSnapshot toSnapshot(
		NewsRegionBucket bucket,
		YearMonth month,
		String methodVersion,
		int monthNewsCount,
		List<BigKindsRow> bucketRows
	) {
		List<RegionMonthSignalEvidence> evidence = bucketRows.stream()
			.filter(row -> !ForbiddenNewsTextGuard.hasForbiddenText(row.title()))
			.limit(CSV_EVIDENCE_LIMIT)
			.map(row -> new RegionMonthSignalEvidence(
				"BIGKINDS_CSV:" + row.providerRecordId(),
				row.title(),
				row.publisher(),
				row.publishedDate(),
				row.url(),
				row.citationUrl(),
				topicTags(row),
				RegionMonthSignalEvidenceScope.DIRECT
			))
			.toList();
		Score score = score(bucketRows);
		BigDecimal confidence = confidence(bucket, bucketRows, evidence);
		String note = evidence.isEmpty()
			? bucket.titleKo() + " " + month + " metadata signal 없음"
			: bucket.titleKo() + " " + month + " metadata " + evidence.size() + "건 기반 aggregate signal";
		return new RegionMonthSignalSnapshot(
			bucket,
			month.atDay(1),
			RegionMonthSignalSourceKind.BIGKINDS_CSV,
			methodVersion,
			NewsModelDatasetTier.EXPERIMENTAL_SEED,
			monthNewsCount,
			bucketRows.size(),
			evidence.size(),
			0,
			score.policyPositive(),
			score.policyNegative(),
			score.redevelopment(),
			score.transport(),
			score.supplyRisk(),
			score.saleMarket(),
			score.rentalMarket(),
			score.priceUp(),
			score.priceDown(),
			confidence,
			note,
			evidence
		);
	}

	private BigDecimal confidence(NewsRegionBucket bucket, List<BigKindsRow> bucketRows, List<RegionMonthSignalEvidence> evidence) {
		if (evidence.isEmpty()) {
			return new BigDecimal("0.300");
		}
		double evidenceDepth = Math.min(evidence.size(), CSV_EVIDENCE_LIMIT) / (double) CSV_EVIDENCE_LIMIT * 0.18;
		double matchedDepth = matchedDepth(bucketRows.size());
		double topicRatio = evidenceQualityRows(bucketRows, evidence.size()) / (double) evidence.size();
		double numericRatio = numericSignalRows(bucketRows, evidence.size()) / (double) evidence.size();
		double titleRegionRatio = titleRegionRows(bucket, bucketRows, evidence.size()) / (double) evidence.size();
		double sourceDiversity = sourceDiversity(evidence);
		double value = 0.25
			+ evidenceDepth
			+ matchedDepth
			+ topicRatio * 0.16
			+ numericRatio * 0.12
			+ titleRegionRatio * 0.17
			+ sourceDiversity * 0.04;
		return BigDecimal.valueOf(Math.min(0.950, value)).setScale(3, RoundingMode.HALF_UP);
	}

	private static double matchedDepth(int matchedNewsCount) {
		if (matchedNewsCount >= 500) {
			return 0.10;
		}
		if (matchedNewsCount >= 100) {
			return 0.08;
		}
		if (matchedNewsCount >= 30) {
			return 0.05;
		}
		if (matchedNewsCount >= 10) {
			return 0.04;
		}
		return 0.02;
	}

	private static long evidenceQualityRows(List<BigKindsRow> bucketRows, int evidenceSize) {
		return bucketRows.stream()
			.filter(row -> !ForbiddenNewsTextGuard.hasForbiddenText(row.title()))
			.limit(evidenceSize)
			.filter(row -> containsAny(
				row.title(),
				"재건축",
				"재개발",
				"정비사업",
				"교통",
				"철도",
				"GTX",
				"지하철",
				"역세권",
				"공급",
				"분양",
				"미분양",
				"입주",
				"전세",
				"월세",
				"임대",
				"규제",
				"대책",
				"세금",
				"금리",
				"대출",
				"매매",
				"거래",
				"가격",
				"상승",
				"하락"
			))
			.count();
	}

	private static long numericSignalRows(List<BigKindsRow> bucketRows, int evidenceSize) {
		return bucketRows.stream()
			.filter(row -> !ForbiddenNewsTextGuard.hasForbiddenText(row.title()))
			.limit(evidenceSize)
			.filter(row -> {
				String text = row.title();
				return NUMERIC_SIGNAL_PATTERN.matcher(text).find()
					&& containsAny(text, "가격", "매매", "전세", "월세", "거래", "상승", "하락", "분양", "공급");
			})
			.count();
	}

	private long titleRegionRows(NewsRegionBucket bucket, List<BigKindsRow> bucketRows, int evidenceSize) {
		return bucketRows.stream()
			.filter(row -> !ForbiddenNewsTextGuard.hasForbiddenText(row.title()))
			.limit(evidenceSize)
			.filter(row -> regionAliasMatcher.match(row.title()).contains(bucket))
			.count();
	}

	private static double sourceDiversity(List<RegionMonthSignalEvidence> evidence) {
		long distinctPublishers = evidence.stream().map(RegionMonthSignalEvidence::publisher).distinct().count();
		return Math.min(1.0, distinctPublishers / (double) Math.min(evidence.size(), 5));
	}

	private List<BigKindsRow> readRows(Path csvInputDir) {
		if (!Files.exists(csvInputDir)) {
			return List.of();
		}
		List<BigKindsRow> rows = new ArrayList<>();
		try (var paths = Files.walk(csvInputDir)) {
			for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".csv")).sorted().toList()) {
				rows.addAll(readFile(path));
			}
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to read BigKinds CSV directory: " + csvInputDir, ex);
		}
		return rows;
	}

	private List<BigKindsRow> readFile(Path path) {
		String content = readContent(path);
		List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
		if (lines.isEmpty()) {
			return List.of();
		}
		char delimiter = lines.get(0).contains("\t") ? '\t' : ',';
		List<String> header = parseLine(stripBom(lines.get(0)), delimiter);
		Map<String, Integer> index = index(header);
		if (!index.containsKey("일자") || !index.containsKey("언론사") || !index.containsKey("제목")) {
			return List.of();
		}
		List<BigKindsRow> rows = new ArrayList<>();
		for (int i = 1; i < lines.size(); i++) {
			List<String> columns = parseLine(lines.get(i), delimiter);
			LocalDate publishedDate = parseDate(value(columns, index, "일자"));
			if (publishedDate == null) {
				continue;
			}
			String title = value(columns, index, "제목");
			String publisher = value(columns, index, "언론사");
			String citationUrl = firstNonBlank(value(columns, index, "주소"), "BIGKINDS_CSV:" + path.getFileName() + ":" + (i + 1));
			String providerRecordId = providerRecordId(citationUrl, path, i + 1);
			String url = firstNonBlank(value(columns, index, "원본주소"), citationUrl);
			rows.add(new BigKindsRow(
				publishedDate,
				firstNonBlank(publisher, "BigKinds"),
				firstNonBlank(title, "제목 미확인"),
				url,
				citationUrl,
				providerRecordId,
				String.join(" ", title, value(columns, index, "개체명(지역)"), value(columns, index, "키워드"), value(columns, index, "특성추출"))
			));
		}
		return rows;
	}

	private String readContent(Path path) {
		try {
			byte[] bytes = Files.readAllBytes(path);
			String utf8 = new String(bytes, StandardCharsets.UTF_8);
			if (looksLikeBigKindsHeader(utf8)) {
				return utf8;
			}
			String ms949 = new String(bytes, Charset.forName("MS949"));
			if (looksLikeBigKindsHeader(ms949)) {
				return ms949;
			}
			return utf8.indexOf('\uFFFD') < 0 ? utf8 : ms949;
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to read CSV file: " + path, ex);
		}
	}

	private boolean looksLikeBigKindsHeader(String content) {
		String firstLine = content.lines().findFirst().orElse("");
		return firstLine.contains("주소")
			&& firstLine.contains("일자")
			&& firstLine.contains("언론사")
			&& firstLine.contains("제목");
	}

	private static List<String> parseLine(String line, char delimiter) {
		List<String> values = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '"') {
				if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					current.append('"');
					i++;
				}
				else {
					quoted = !quoted;
				}
			}
			else if (c == delimiter && !quoted) {
				values.add(current.toString().strip());
				current.setLength(0);
			}
			else {
				current.append(c);
			}
		}
		values.add(current.toString().strip());
		return values;
	}

	private static Map<String, Integer> index(List<String> header) {
		Map<String, Integer> index = new LinkedHashMap<>();
		for (int i = 0; i < header.size(); i++) {
			index.put(header.get(i).strip(), i);
		}
		return index;
	}

	private static String value(List<String> columns, Map<String, Integer> index, String key) {
		Integer position = index.get(key);
		if (position == null || position >= columns.size()) {
			return "";
		}
		return columns.get(position).strip();
	}

	private static String stripBom(String value) {
		return value.startsWith("\uFEFF") ? value.substring(1) : value;
	}

	private static LocalDate parseDate(String value) {
		try {
			return value == null || value.isBlank() ? null : LocalDate.parse(value.strip().replace('/', '-'));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static String firstNonBlank(String first, String fallback) {
		return first == null || first.isBlank() ? fallback : first.strip();
	}

	private static String providerRecordId(String citationUrl, Path path, int rowNumber) {
		Matcher matcher = NEWS_ID_PATTERN.matcher(citationUrl);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return path.getFileName() + ":" + rowNumber;
	}

	private static List<String> topicTags(BigKindsRow row) {
		String text = row.searchText();
		List<String> tags = new ArrayList<>();
		if (containsAny(text, "재건축", "재개발", "정비사업")) {
			tags.add("redevelopment");
		}
		if (containsAny(text, "교통", "철도", "GTX", "지하철", "역세권")) {
			tags.add("transport");
		}
		if (containsAny(text, "공급", "분양", "미분양", "입주")) {
			tags.add("supply");
		}
		if (containsAny(text, "전세", "임대", "월세")) {
			tags.add("rental_market");
		}
		if (containsAny(text, "규제", "대책", "세금", "금리", "대출")) {
			tags.add("policy");
		}
		return tags.isEmpty() ? List.of("market") : List.copyOf(tags);
	}

	private static Score score(List<BigKindsRow> rows) {
		int policyPositive = 0;
		int policyNegative = 0;
		int redevelopment = 0;
		int transport = 0;
		int supplyRisk = 0;
		int saleMarket = 0;
		int rentalMarket = 0;
		for (BigKindsRow row : rows) {
			String text = row.searchText();
			policyPositive = Math.max(policyPositive, containsAny(text, "완화", "해제", "지원") ? 50 : 0);
			policyNegative = Math.max(policyNegative, containsAny(text, "규제", "강화", "대책", "세금", "대출") ? 50 : 0);
			redevelopment = Math.max(redevelopment, containsAny(text, "재건축", "재개발", "정비사업") ? 50 : 0);
			transport = Math.max(transport, containsAny(text, "교통", "철도", "GTX", "지하철", "역세권") ? 50 : 0);
			supplyRisk = Math.max(supplyRisk, containsAny(text, "공급부족", "입주물량", "분양", "미분양") ? 50 : 0);
			saleMarket = Math.max(saleMarket, containsAny(text, "매매", "거래", "가격", "상승", "하락") ? 50 : 0);
			rentalMarket = Math.max(rentalMarket, containsAny(text, "전세", "월세", "임대") ? 50 : 0);
		}
		int priceUp = Math.min(100, Math.max(saleMarket, policyPositive) + Math.max(redevelopment, transport) / 2);
		int priceDown = Math.min(100, Math.max(policyNegative, supplyRisk) + rentalMarket / 2);
		return new Score(policyPositive, policyNegative, redevelopment, transport, supplyRisk, saleMarket, rentalMarket, priceUp, priceDown);
	}

	private static boolean containsAny(String text, String... needles) {
		for (String needle : needles) {
			if (text.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private record BigKindsRow(
		LocalDate publishedDate,
		String publisher,
		String title,
		String url,
		String citationUrl,
		String providerRecordId,
		String searchText
	) {
	}

	private record Score(
		int policyPositive,
		int policyNegative,
		int redevelopment,
		int transport,
		int supplyRisk,
		int saleMarket,
		int rentalMarket,
		int priceUp,
		int priceDown
	) {
	}
}
