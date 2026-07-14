package com.home.user.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("home.cookie")
public record CookieProperties(boolean secure) {}
