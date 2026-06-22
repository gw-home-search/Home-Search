package com.home.news.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsRegionBucket;

public class RegionMonthSignalWebWorklistGenerator {

	public static final int WEB_EVIDENCE_TARGET = 5;
	private final ObjectMapper objectMapper;

	public RegionMonthSignalWebWorklistGenerator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public int write(Path outputPath, YearMonth startInclusive, YearMonth endInclusive) {
		try {
			Files.createDirectories(outputPath.getParent());
			int count = 0;
			try (var writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
				for (YearMonth month = startInclusive; !month.isAfter(endInclusive); month = month.plusMonths(1)) {
					for (NewsRegionBucket bucket : NewsRegionBucket.values()) {
						writer.write(objectMapper.writeValueAsString(row(month, bucket)));
						writer.newLine();
						count++;
					}
				}
			}
			return count;
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to write web worklist: " + outputPath, ex);
		}
	}

	private Map<String, Object> row(YearMonth month, NewsRegionBucket bucket) {
		String monthKo = month.getYear() + "년 " + month.getMonthValue() + "월";
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("region_bucket", bucket.name());
		row.put("region_name", bucket.titleKo());
		row.put("signal_month", month.toString());
		row.put("evidence_target", WEB_EVIDENCE_TARGET);
		row.put("evidence_policy", "metadata links only; no copied article text");
		row.put("queries", java.util.List.of(
			bucket.titleKo() + " 아파트 " + monthKo,
			bucket.titleKo() + " 부동산 " + monthKo,
			bucket.titleKo() + " 재건축 교통 공급 전세 " + monthKo,
			"서울 경기 전국 부동산 정책 아파트 " + monthKo
		));
		return row;
	}
}
