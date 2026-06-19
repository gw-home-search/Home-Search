package com.home.news.application;

public record SignalProfileCommand(
	String extractionVersion,
	String provider,
	String model,
	String promptVersion,
	String schemaVersion,
	String promptHash,
	String jsonSchemaHash,
	boolean active
) {
}
