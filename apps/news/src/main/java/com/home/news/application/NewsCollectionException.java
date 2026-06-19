package com.home.news.application;

public class NewsCollectionException extends RuntimeException {

	public NewsCollectionException(String message) {
		super(message);
	}

	public NewsCollectionException(String message, Throwable cause) {
		super(message, cause);
	}
}
