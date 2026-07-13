package com.home.application.favorite;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteComplex;
import java.util.Optional;

public final class GetFavoriteComplex {
    private final FavoriteComplexRepository repository;
    public GetFavoriteComplex(FavoriteComplexRepository repository) { this.repository = repository; }
    public Optional<FavoriteComplex> execute(long userId, long complexId) {
        SaveFavoriteComplex.validate(userId, complexId);
        return repository.get(userId, complexId);
    }
}
