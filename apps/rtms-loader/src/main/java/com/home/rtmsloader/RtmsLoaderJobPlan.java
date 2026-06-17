package com.home.rtmsloader;

import java.util.List;

public record RtmsLoaderJobPlan(
	RtmsLoaderMode mode,
	List<RtmsLoaderMonthRequest> months
) {

	public RtmsLoaderJobPlan {
		if (mode == null) {
			throw new IllegalArgumentException("mode is required");
		}
		months = months == null ? List.of() : List.copyOf(months);
		if (months.isEmpty()) {
			throw new IllegalArgumentException("RTMS loader plan requires at least one month request");
		}
	}
}
