package com.home.infrastructure.web;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebCorsConfiguration {

	private static final String LOCALHOST = "localhost";
	private static final String LOOPBACK = "127.0.0.1";

	@Bean
	WebMvcConfigurer apiCorsWebMvcConfigurer(
		@Value("${FRONTEND_URL:http://localhost:5173}") String frontendUrl
	) {
		String[] allowedOrigins = localLoopbackOrigins(frontendUrl).toArray(String[]::new);
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/api/**")
					.allowedOrigins(allowedOrigins)
					.allowedMethods("GET", "POST", "PUT", "OPTIONS")
					.allowedHeaders("*")
					.maxAge(3600);
			}
		};
	}

	private Set<String> localLoopbackOrigins(String frontendUrl) {
		Set<String> origins = new LinkedHashSet<>();
		String normalized = normalize(frontendUrl);
		if (normalized.isBlank()) {
			return origins;
		}
		origins.add(normalized);
		if (normalized.contains(LOCALHOST)) {
			origins.add(normalized.replace(LOCALHOST, LOOPBACK));
		}
		else if (normalized.contains(LOOPBACK)) {
			origins.add(normalized.replace(LOOPBACK, LOCALHOST));
		}
		return origins;
	}

	private String normalize(String frontendUrl) {
		return frontendUrl == null ? "" : frontendUrl.trim().replaceAll("/+$", "");
	}
}
