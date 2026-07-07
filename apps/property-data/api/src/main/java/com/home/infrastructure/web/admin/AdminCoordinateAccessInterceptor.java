package com.home.infrastructure.web.admin;

import com.home.application.coordinate.override.AdminCoordinateAccessDeniedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

class AdminCoordinateAccessInterceptor implements HandlerInterceptor {

	static final String ACCESS_CODE_HEADER = "X-Admin-Access-Code";

	private final String accessCode;

	AdminCoordinateAccessInterceptor(String accessCode) {
		this.accessCode = accessCode;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}

		String requestAccessCode = request.getHeader(ACCESS_CODE_HEADER);
		if (!accessCode.equals(requestAccessCode)) {
			throw new AdminCoordinateAccessDeniedException();
		}
		return true;
	}
}
