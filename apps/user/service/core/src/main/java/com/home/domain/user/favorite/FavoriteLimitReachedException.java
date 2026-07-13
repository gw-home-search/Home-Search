package com.home.domain.user.favorite;

public final class FavoriteLimitReachedException extends RuntimeException {
    public FavoriteLimitReachedException() {
        super("favorite limit reached");
    }
}
