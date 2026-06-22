package com.home.news.application;

public class NewsSignalValidationException extends RuntimeException {

	public NewsSignalValidationException(String message) {
		super(message);
	}

	public NewsSignalValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
