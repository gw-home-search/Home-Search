package com.home.application.favorite.port;

import com.home.domain.user.favorite.FavoriteComplex;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FavoriteComplexRepository {
    FavoriteComplex save(long userId, long complexId, Instant savedAt, FavoriteLimitPolicy policy);

    void remove(long userId, long complexId);

    Optional<FavoriteComplex> get(long userId, long complexId);

    FavoritePage list(long userId, int page, int size);

    record FavoritePage(List<FavoriteComplex> content, long totalElements) {
        public FavoritePage {
            content = List.copyOf(content);
            if (totalElements < 0) throw new IllegalArgumentException("totalElements must not be negative");
        }
    }
}
