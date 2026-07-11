package com.home.infrastructure.web.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.home.application.coordinate.override.AdminCoordinateAccessDeniedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

class AdminCoordinateAccessInterceptor implements HandlerInterceptor {

	static final String ACCESS_CODE_HEADER = "X-Admin-Access-Code";

	private final String accessCode;

	AdminCoordinateAccessInterceptor(String accessCode) {
		if (accessCode == null || accessCode.isBlank()) {
			throw new IllegalStateException("admin access code must be configured when the admin surface is enabled");
		}
		this.accessCode = accessCode;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}

		String requestAccessCode = request.getHeader(ACCESS_CODE_HEADER);
		if (requestAccessCode == null || !MessageDigest.isEqual(
			accessCode.getBytes(StandardCharsets.UTF_8),
			requestAccessCode.getBytes(StandardCharsets.UTF_8)
		)) {
			throw new AdminCoordinateAccessDeniedException();
		}
		return true;
	}
}
