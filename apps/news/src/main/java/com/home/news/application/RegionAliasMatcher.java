package com.home.news.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.home.domain.news.NewsRegionBucket;

public class RegionAliasMatcher {

	private final Map<NewsRegionBucket, List<String>> aliases;

	public RegionAliasMatcher() {
		this.aliases = aliases();
	}

	public Set<NewsRegionBucket> match(String text) {
		String normalized = normalize(text);
		Set<NewsRegionBucket> buckets = new LinkedHashSet<>();
		if (normalized.isBlank()) {
			return buckets;
		}
		for (Map.Entry<NewsRegionBucket, List<String>> entry : aliases.entrySet()) {
			for (String alias : entry.getValue()) {
				if (normalized.contains(normalize(alias))) {
					buckets.add(entry.getKey());
					break;
				}
			}
		}
		if (buckets.stream().anyMatch(bucket -> bucket.name().startsWith("SEOUL_"))) {
			buckets.add(NewsRegionBucket.SEOUL);
		}
		if (buckets.stream().anyMatch(bucket -> bucket.name().startsWith("GYEONGGI_"))) {
			buckets.add(NewsRegionBucket.GYEONGGI);
		}
		if (normalized.contains("전국") || normalized.contains("정부") || normalized.contains("국토교통부")) {
			buckets.add(NewsRegionBucket.NATIONAL);
		}
		return buckets;
	}

	private static Map<NewsRegionBucket, List<String>> aliases() {
		Map<NewsRegionBucket, List<String>> map = new EnumMap<>(NewsRegionBucket.class);
		put(map, NewsRegionBucket.NATIONAL, "전국", "정부", "국토교통부", "부동산 대책", "주택정책");
		put(map, NewsRegionBucket.SEOUL, "서울", "서울시");
		put(map, NewsRegionBucket.GYEONGGI, "경기", "경기도", "수도권");
		put(map, NewsRegionBucket.SEOUL_GANGNAM_GU, "강남구", "강남", "대치동", "개포동", "압구정");
		put(map, NewsRegionBucket.SEOUL_SEOCHO_GU, "서초구", "서초", "반포동", "잠원동", "방배동");
		put(map, NewsRegionBucket.SEOUL_SONGPA_GU, "송파구", "송파", "잠실", "잠실동", "가락동");
		put(map, NewsRegionBucket.SEOUL_YONGSAN_GU, "용산구", "용산", "한남동", "이촌동");
		put(map, NewsRegionBucket.SEOUL_MAPO_GU, "마포구", "마포", "상암동", "아현동");
		put(map, NewsRegionBucket.SEOUL_SEONGDONG_GU, "성동구", "성동", "성수동", "왕십리", "옥수동");
		put(map, NewsRegionBucket.SEOUL_YEONGDEUNGPO_GU, "영등포구", "영등포", "여의도");
		put(map, NewsRegionBucket.SEOUL_YANGCHEON_GU, "양천구", "양천", "목동");
		put(map, NewsRegionBucket.SEOUL_NOWON_GU, "노원구", "노원", "상계동", "중계동");
		put(map, NewsRegionBucket.SEOUL_GANGDONG_GU, "강동구", "강동", "고덕동", "둔촌동");
		put(map, NewsRegionBucket.GYEONGGI_SEONGNAM_SI, "성남시", "성남", "분당", "판교");
		put(map, NewsRegionBucket.GYEONGGI_GWACHEON_SI, "과천시", "과천");
		put(map, NewsRegionBucket.GYEONGGI_HANAM_SI, "하남시", "하남", "미사");
		put(map, NewsRegionBucket.GYEONGGI_GWANGMYEONG_SI, "광명시", "광명");
		put(map, NewsRegionBucket.GYEONGGI_GOYANG_SI, "고양시", "고양", "일산");
		put(map, NewsRegionBucket.GYEONGGI_YONGIN_SI, "용인시", "용인", "수지", "기흥");
		put(map, NewsRegionBucket.GYEONGGI_SUWON_SI, "수원시", "수원", "광교");
		put(map, NewsRegionBucket.GYEONGGI_HWASEONG_SI, "화성시", "화성", "동탄");
		put(map, NewsRegionBucket.GYEONGGI_NAMYANGJU_SI, "남양주시", "남양주", "다산");
		put(map, NewsRegionBucket.GYEONGGI_GIMPO_SI, "김포시", "김포");
		put(map, NewsRegionBucket.GYEONGGI_ANYANG_SI, "안양시", "안양", "평촌");
		put(map, NewsRegionBucket.GYEONGGI_UIWANG_SI, "의왕시", "의왕");
		return Map.copyOf(map);
	}

	private static void put(Map<NewsRegionBucket, List<String>> map, NewsRegionBucket bucket, String... aliases) {
		map.put(bucket, List.of(aliases));
	}

	private static String normalize(String text) {
		if (text == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) {
				builder.append(c);
			}
		}
		return builder.toString().toLowerCase();
	}

	public List<NewsRegionBucket> allBuckets() {
		return new ArrayList<>(List.of(NewsRegionBucket.values()));
	}
}
