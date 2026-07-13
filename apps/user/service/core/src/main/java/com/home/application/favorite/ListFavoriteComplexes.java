package com.home.application.favorite;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.application.favorite.port.FavoriteComplexRepository.FavoritePage;

public final class ListFavoriteComplexes {
    private final FavoriteComplexRepository repository;
    public ListFavoriteComplexes(FavoriteComplexRepository repository) { this.repository = repository; }
    public FavoritePage execute(long userId, int page, int size) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (page < 0 || size < 1 || size > 100) throw new InvalidPaginationException();
        return repository.list(userId, page, size);
    }
}
