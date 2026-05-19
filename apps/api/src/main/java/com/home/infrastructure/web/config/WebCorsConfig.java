package com.home.infrastructure.web.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 로컬 V1 web app이 map API를 호출할 수 있도록 CORS preflight를 허용하는 MVC config입니다.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

	private final String allowedOrigins;

	/**
	 * @param allowedOrigins comma-separated web origins allowed to call V1 APIs
	 */
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
