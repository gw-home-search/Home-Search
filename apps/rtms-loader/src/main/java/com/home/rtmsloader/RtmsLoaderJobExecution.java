package com.home.rtmsloader;

public record RtmsLoaderJobExecution(
	RtmsLoaderMode mode,
	int plannedMonthCount
) {
}
