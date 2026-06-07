package com.home.application.news;

public record NewsSignalFeatureExtractionResult(
	long evaluated,
	long extracted,
	long statusUpdated,
	long duplicateFeatureSkipped
) {

	public static NewsSignalFeatureExtractionResult empty() {
		return new NewsSignalFeatureExtractionResult(0, 0, 0, 0);
	}
}
