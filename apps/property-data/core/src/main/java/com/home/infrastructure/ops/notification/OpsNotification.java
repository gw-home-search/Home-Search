package com.home.infrastructure.ops.notification;

import java.util.Objects;

public record OpsNotification(
	String eventType,
	String title,
	String message
) {

	public OpsNotification {
		eventType = requireText(eventType, "eventType");
		title = requireText(title, "title");
		message = requireText(message, "message");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " is required");
		}
		return Objects.requireNonNull(value).trim();
	}
}
