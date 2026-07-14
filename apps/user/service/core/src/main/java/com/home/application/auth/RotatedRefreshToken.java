package com.home.application.auth;

import java.time.Instant;

public record RotatedRefreshToken(long userId, String rawToken, Instant expiresAt) {}
