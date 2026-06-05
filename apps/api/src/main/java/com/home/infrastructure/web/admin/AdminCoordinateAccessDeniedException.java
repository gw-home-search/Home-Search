package com.home.infrastructure.web.admin;

public class AdminCoordinateAccessDeniedException extends RuntimeException {

	public AdminCoordinateAccessDeniedException() {
		super("admin coordinate access code is missing or invalid");
	}
}
