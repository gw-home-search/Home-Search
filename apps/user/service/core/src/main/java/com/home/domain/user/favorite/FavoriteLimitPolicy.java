package com.home.domain.user.favorite;

public final class FavoriteLimitPolicy {
    public static final int MAXIMUM_FAVORITES = 200;

    public void ensureCanSave(boolean alreadySaved, long currentCount) {
        if (currentCount < 0) throw new IllegalArgumentException("currentCount must not be negative");
        if (!alreadySaved && currentCount >= MAXIMUM_FAVORITES) {
            throw new FavoriteLimitReachedException();
        }
    }
}
