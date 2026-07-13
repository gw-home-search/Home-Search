package com.home.application.read;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class PropertyReadUseCase {

	private static final int SUGGESTION_LIMIT = 8;
	private static final int MAX_SEARCH_QUERY_CODE_POINTS = 100;
	private static final int MAX_SEARCH_QUERY_TOKENS = 8;
	private static final int DEFAULT_REGION_COMPLEX_LIMIT = 50;
	private static final int MAX_REGION_COMPLEX_LIMIT = 100;
	private static final int DEFAULT_TRADE_PAGE_SIZE = 25;
	private static final int MAX_TRADE_PAGE_SIZE = 100;

	private final PropertyReadRepository repository;

	public PropertyReadUseCase(PropertyReadRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public List<SearchComplexResult> searchComplexes(String query) {
		String normalized = normalizeSearchQuery(query);
		if (normalized.isEmpty()) {
			return List.of();
		}
		return repository.searchComplexes(normalized);
	}

	public List<ComplexSuggestionResult> suggestComplexes(String query) {
		String normalized = normalizeSearchQuery(query);
		if (normalized.isEmpty()) {
			return List.of();
		}
		return repository.suggestComplexes(normalized, SUGGESTION_LIMIT);
	}

	private String normalizeSearchQuery(String query) {
		String trimmed = query == null ? "" : query.strip();
		if (trimmed.codePointCount(0, trimmed.length()) > MAX_SEARCH_QUERY_CODE_POINTS) {
			throw new InvalidReadRequestException("search query must not exceed 100 characters");
		}
		if (trimmed.isEmpty()) {
			return "";
		}
		LinkedHashMap<String, String> uniqueTokens = new LinkedHashMap<>();
		for (String token : trimmed.split("(?U)\\s+")) {
			uniqueTokens.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
		}
		if (uniqueTokens.size() > MAX_SEARCH_QUERY_TOKENS) {
			throw new InvalidReadRequestException("search query must not exceed 8 unique tokens");
		}
		return String.join(" ", uniqueTokens.values());
	}

	public List<RegionSummaryResult> getRootRegions() {
		return repository.findRootRegions();
	}

	public RegionDetailResult getRegionDetail(Long regionId) {
		return repository.findRegionDetail(regionId)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public List<ComplexSummaryResult> getRegionComplexes(Long regionId, Integer requestedLimit, Integer requestedOffset) {
		int limit = normalizeLimit(requestedLimit);
		int offset = normalizeOffset(requestedOffset);
		return repository.findRegionComplexes(regionId, limit, offset)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	public ParcelDetailResult getParcelDetail(Long parcelId) {
		return getParcelDetail(parcelId, null);
	}

	public ParcelDetailResult getParcelDetail(Long parcelId, Long complexId) {
		return repository.findParcelDetail(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel detail not found: " + parcelId));
	}

	public List<ComplexSummaryResult> getParcelComplexes(Long parcelId) {
		return repository.findParcelComplexes(parcelId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel not found: " + parcelId));
	}

	public ParcelDetailResult getComplexDetail(Long complexId) {
		return repository.findComplexDetail(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex detail not found: " + complexId));
	}

	public TradeListResult getTradeList(Long parcelId) {
		return getTradeList(parcelId, null);
	}

	public TradeListResult getTradeList(Long parcelId, Long complexId) {
		return getTradeList(parcelId, complexId, null, null);
	}

	public TradeListResult getTradeList(Long parcelId, Long complexId, Integer requestedPage, Integer requestedSize) {
		int page = normalizePage(requestedPage);
		int size = normalizeSize(requestedSize);
		return repository.findTradeList(parcelId, complexId, page, size)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}

	public TradeListResult getComplexTradeList(Long complexId) {
		return getComplexTradeList(complexId, null, null);
	}

	public TradeListResult getComplexTradeList(Long complexId, Integer requestedPage, Integer requestedSize) {
		int page = normalizePage(requestedPage);
		int size = normalizeSize(requestedSize);
		return repository.findComplexTradeList(complexId, page, size)
			.orElseThrow(() -> new ResourceNotFoundException("complex trade parent not found: " + complexId));
	}

	public List<TradeTrendPoint> getTradeTrend(Long parcelId, Long complexId) {
		return repository.findTradeTrend(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}

	public List<TradeTrendPoint> getComplexTradeTrend(Long complexId) {
		return repository.findComplexTradeTrend(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex trade parent not found: " + complexId));
	}

	private int normalizeLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_REGION_COMPLEX_LIMIT;
		}
		if (requestedLimit < 1) {
			throw new InvalidReadRequestException("limit must be greater than 0");
		}
		return Math.min(requestedLimit, MAX_REGION_COMPLEX_LIMIT);
	}

	private int normalizeOffset(Integer requestedOffset) {
		if (requestedOffset == null) {
			return 0;
		}
		if (requestedOffset < 0) {
			throw new InvalidReadRequestException("offset must be greater than or equal to 0");
		}
		return requestedOffset;
	}

	private int normalizePage(Integer requestedPage) {
		if (requestedPage == null) {
			return 0;
		}
		if (requestedPage < 0) {
			throw new InvalidReadRequestException("page must be greater than or equal to 0");
		}
		return requestedPage;
	}

	private int normalizeSize(Integer requestedSize) {
		if (requestedSize == null) {
			return DEFAULT_TRADE_PAGE_SIZE;
		}
		if (requestedSize < 1) {
			throw new InvalidReadRequestException("size must be greater than 0");
		}
		return Math.min(requestedSize, MAX_TRADE_PAGE_SIZE);
	}
}
