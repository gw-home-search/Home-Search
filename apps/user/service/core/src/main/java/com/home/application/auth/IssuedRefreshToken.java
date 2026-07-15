package com.home.application.auth;

import java.time.Instant;

public record IssuedRefreshToken(long userId, String rawToken, Instant expiresAt) {}
