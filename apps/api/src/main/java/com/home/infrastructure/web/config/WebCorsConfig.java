package com.home.infrastructure.web.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

	private final String allowedOrigins;

	public WebCorsConfig(
		@Value("${app.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") String allowedOrigins
	) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/v1/**")
			.allowedOrigins(origins())
			.allowedMethods("GET", "POST", "OPTIONS")
			.allowedHeaders("*");
	}

	private String[] origins() {
		return Arrays.stream(allowedOrigins.split(","))
			.map(String::trim)
			.filter(origin -> !origin.isEmpty())
			.toArray(String[]::new);
	}
}
