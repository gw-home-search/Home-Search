package com.home.infrastructure.web.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "home.admin.coordinate-override.enabled", havingValue = "true")
class AdminCoordinateAccessConfiguration implements WebMvcConfigurer {

	private final String accessCode;

	AdminCoordinateAccessConfiguration(
		@Value("${home.admin.coordinate-override.access-code:local-admin}") String accessCode
	) {
		this.accessCode = accessCode;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AdminCoordinateAccessInterceptor(accessCode))
			.addPathPatterns("/api/v1/admin/coordinates/**");
	}
}
