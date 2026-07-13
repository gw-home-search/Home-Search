package com.home.application.regionnavigation;

import java.util.List;
import java.util.Objects;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.InvalidReadRequestException;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.ResourceNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegionNavigationService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 100;

	private final RegionNavigationReader reader;

	public RegionNavigationService(RegionNavigationReader reader) {
		this.reader = Objects.requireNonNull(reader);
	}

	public List<RegionSummaryResult> getRootRegions() {
		return reader.findRootRegions();
	}

	@Transactional(
		readOnly = true,
		isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
	)
	public RegionDetailResult getRegionDetail(Long regionId) {
		return reader.findRegionDetail(regionId)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	@Transactional(
		readOnly = true,
		isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
	)
	public List<ComplexSummaryResult> getRegionComplexes(Long regionId, Integer requestedLimit, Integer requestedOffset) {
		int limit = normalizeLimit(requestedLimit);
		int offset = normalizeOffset(requestedOffset);
		return reader.findRegionComplexes(regionId, limit, offset)
			.orElseThrow(() -> new ResourceNotFoundException("region not found: " + regionId));
	}

	private int normalizeLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_LIMIT;
		}
		if (requestedLimit < 1) {
			throw new InvalidReadRequestException("limit must be greater than 0");
		}
		return Math.min(requestedLimit, MAX_LIMIT);
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
}
