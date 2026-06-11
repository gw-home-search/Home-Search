package com.home.application.coordinate.override;

public class AdminCoordinateAccessDeniedException extends RuntimeException {

	public AdminCoordinateAccessDeniedException() {
		super("admin coordinate access code is missing or invalid");
	}
}
