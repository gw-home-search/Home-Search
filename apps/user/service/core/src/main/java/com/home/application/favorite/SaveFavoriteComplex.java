package com.home.application.favorite;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteComplex;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.time.Clock;

public final class SaveFavoriteComplex {
    private final FavoriteComplexRepository repository;
    private final FavoriteLimitPolicy policy;
    private final Clock clock;

    public SaveFavoriteComplex(FavoriteComplexRepository repository, FavoriteLimitPolicy policy, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.clock = clock;
    }

    public FavoriteComplex execute(long userId, long complexId) {
        validate(userId, complexId);
        return repository.save(userId, complexId, clock.instant(), policy);
    }

    static void validate(long userId, long complexId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (complexId <= 0) throw new InvalidComplexIdException();
    }
}
