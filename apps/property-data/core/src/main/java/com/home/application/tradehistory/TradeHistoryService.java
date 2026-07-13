package com.home.application.tradehistory;

import java.util.List;
import java.util.Objects;

import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.ResourceNotFoundException;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeTrendPoint;

import org.springframework.stereotype.Service;

@Service
public class TradeHistoryService {

	private static final int DEFAULT_PAGE_SIZE = 25;
	private static final int MAX_PAGE_SIZE = 100;

	private final TradeHistoryReader reader;

	public TradeHistoryService(TradeHistoryReader reader) {
		this.reader = Objects.requireNonNull(reader);
	}

	public TradeListResult getTradeList(Long parcelId, Long complexId, Integer requestedPage, Integer requestedSize) {
		int page = normalizePage(requestedPage);
		int size = normalizeSize(requestedSize);
		return reader.findTradeList(parcelId, complexId, page, size)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}

	public TradeListResult getComplexTradeList(Long complexId, Integer requestedPage, Integer requestedSize) {
		int page = normalizePage(requestedPage);
		int size = normalizeSize(requestedSize);
		return reader.findComplexTradeList(complexId, page, size)
			.orElseThrow(() -> new ResourceNotFoundException("complex trade parent not found: " + complexId));
	}

	public List<TradeTrendPoint> getTradeTrend(Long parcelId, Long complexId) {
		return reader.findTradeTrend(parcelId, complexId)
			.orElseThrow(() -> new ResourceNotFoundException("parcel trade parent not found: " + parcelId));
	}

	public List<TradeTrendPoint> getComplexTradeTrend(Long complexId) {
		return reader.findComplexTradeTrend(complexId)
			.orElseThrow(() -> new ResourceNotFoundException("complex trade parent not found: " + complexId));
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
			return DEFAULT_PAGE_SIZE;
		}
		if (requestedSize < 1) {
			throw new InvalidReadRequestException("size must be greater than 0");
		}
		return Math.min(requestedSize, MAX_PAGE_SIZE);
	}
}
