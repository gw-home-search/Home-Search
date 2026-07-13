package com.home.application.favorite;

import com.home.application.favorite.port.FavoriteComplexRepository;

public final class RemoveFavoriteComplex {
    private final FavoriteComplexRepository repository;
    public RemoveFavoriteComplex(FavoriteComplexRepository repository) { this.repository = repository; }
    public void execute(long userId, long complexId) {
        SaveFavoriteComplex.validate(userId, complexId);
        repository.remove(userId, complexId);
    }
}
