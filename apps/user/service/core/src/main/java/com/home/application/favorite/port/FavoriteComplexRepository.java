package com.home.application.favorite.port;

import com.home.domain.user.favorite.FavoriteComplex;
import java.util.List;
import java.util.Optional;

public interface FavoriteComplexRepository {
    boolean lockUser(long userId);

    Optional<FavoriteComplex> find(long userId, long complexId);

    long count(long userId);

    FavoriteComplex save(FavoriteComplex favorite);

    void remove(long userId, long complexId);

    FavoritePage list(long userId, int page, int size);

    record FavoritePage(List<FavoriteComplex> content, long totalElements) {
        public FavoritePage {
            content = List.copyOf(content);
            if (totalElements < 0) throw new IllegalArgumentException("totalElements must not be negative");
        }
    }
}
