package com.home.infrastructure.web.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "home.admin.metadata-enrichment.enabled", havingValue = "true")
class AdminMetadataAccessConfiguration implements WebMvcConfigurer {
	private final String accessCode;

	AdminMetadataAccessConfiguration(
		@Value("${home.admin.metadata-enrichment.access-code:local-admin}") String accessCode
	) {
		this.accessCode = accessCode;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AdminCoordinateAccessInterceptor(accessCode))
			.addPathPatterns("/api/v1/admin/metadata/**");
	}
}
