package com.home.domain.user.favorite;

import java.time.Instant;

public record FavoriteComplex(long userId, long complexId, Instant savedAt) {
    public FavoriteComplex {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (complexId <= 0) throw new IllegalArgumentException("complexId must be positive");
        if (savedAt == null) throw new IllegalArgumentException("savedAt is required");
    }
}
