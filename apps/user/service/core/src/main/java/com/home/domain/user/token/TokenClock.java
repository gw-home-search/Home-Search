package com.home.domain.user.token;

import java.time.Instant;

@FunctionalInterface
public interface TokenClock {
    Instant now();
}
