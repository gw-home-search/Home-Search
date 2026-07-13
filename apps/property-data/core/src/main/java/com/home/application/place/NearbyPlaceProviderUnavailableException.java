package com.home.application.place;

public class NearbyPlaceProviderUnavailableException extends RuntimeException {

	public NearbyPlaceProviderUnavailableException(String message) {
		super(message);
	}

	public NearbyPlaceProviderUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
