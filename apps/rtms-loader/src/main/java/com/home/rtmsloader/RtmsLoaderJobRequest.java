package com.home.rtmsloader;

import java.util.List;

public record RtmsLoaderJobRequest(
	RtmsLoaderMode mode,
	List<String> lawdCds,
	String baseDealYmd,
	int lookbackMonths
) {

	public RtmsLoaderJobRequest {
		if (mode == null) {
			mode = RtmsLoaderMode.MONTHLY_BULK;
		}
		lawdCds = lawdCds == null ? List.of() : List.copyOf(lawdCds);
		if (lookbackMonths < 0) {
			throw new IllegalArgumentException("lookbackMonths must be greater than or equal to 0");
		}
	}
}
