package com.home.application.coordinate;

import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

public class CoordinateOverrideAdminService {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;

	private final CoordinateOverrideAdminRepository repository;

	public CoordinateOverrideAdminService(CoordinateOverrideAdminRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	public List<CoordinatePendingComplex> findPendingComplexes(Integer requestedLimit) {
		int limit = normalizeLimit(requestedLimit);
		return repository.findPendingComplexes(limit, 0);
	}

	public List<CoordinatePendingComplex> findPendingComplexes(Integer requestedLimit, Integer requestedOffset) {
		int limit = normalizeLimit(requestedLimit);
		int offset = normalizeOffset(requestedOffset);
		return repository.findPendingComplexes(limit, offset);
	}

	@Transactional
	public CoordinateOverrideApprovalResult approve(String pnu, CoordinateOverrideApprovalCommand command) {
		Objects.requireNonNull(command, "command is required");
		String normalizedPnu = normalizePnu(pnu);
		return repository.approve(new CoordinateOverrideApprovalCommand(
			normalizedPnu,
			command.latitude(),
			command.longitude(),
			command.reason(),
			command.approvedBy()
		));
	}

	private int normalizeLimit(Integer requestedLimit) {
		if (requestedLimit == null) {
			return DEFAULT_LIMIT;
		}
		if (requestedLimit < 1) {
			throw new InvalidCoordinateOverrideException("limit must be greater than 0");
		}
		return Math.min(requestedLimit, MAX_LIMIT);
	}

	private int normalizeOffset(Integer requestedOffset) {
		if (requestedOffset == null) {
			return 0;
		}
		if (requestedOffset < 0) {
			throw new InvalidCoordinateOverrideException("offset must be greater than or equal to 0");
		}
		return requestedOffset;
	}

	private String normalizePnu(String pnu) {
		if (pnu == null || !pnu.trim().matches("\\d{19}")) {
			throw new InvalidCoordinateOverrideException("pnu must be a 19 digit value");
		}
		return pnu.trim();
	}
}
