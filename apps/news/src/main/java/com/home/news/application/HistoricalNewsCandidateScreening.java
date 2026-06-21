package com.home.news.application;

import java.util.List;

public record HistoricalNewsCandidateScreening(
	HistoricalNewsCandidate candidate,
	List<HistoricalNewsCandidateRejectReason> reasons
) {

	public boolean accepted() {
		return reasons.isEmpty();
	}
}
