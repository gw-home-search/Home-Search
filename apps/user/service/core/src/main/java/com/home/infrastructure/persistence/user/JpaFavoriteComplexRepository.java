package com.home.infrastructure.persistence.user;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.application.user.UserNotFoundException;
import com.home.domain.user.favorite.FavoriteComplex;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaFavoriteComplexRepository implements FavoriteComplexRepository {
    private final SpringDataFavoriteComplexRepository favorites;
    public JpaFavoriteComplexRepository(SpringDataFavoriteComplexRepository favorites) { this.favorites = favorites; }

    @Override
    @Transactional
    public FavoriteComplex save(long userId, long complexId, Instant savedAt, FavoriteLimitPolicy policy) {
        favorites.lockUser(userId).orElseThrow(UserNotFoundException::new);
        FavoriteComplexId id = new FavoriteComplexId(userId, complexId);
        Optional<FavoriteComplexJpaEntity> existing = favorites.findById(id);
        policy.ensureCanSave(existing.isPresent(), favorites.countByIdUserId(userId));
        return existing.map(JpaFavoriteComplexRepository::toDomain)
            .orElseGet(() -> toDomain(favorites.save(new FavoriteComplexJpaEntity(userId, complexId, savedAt))));
    }

    @Override
    @Transactional
    public void remove(long userId, long complexId) {
        favorites.deleteByIdUserIdAndIdComplexId(userId, complexId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FavoriteComplex> get(long userId, long complexId) {
        return favorites.findById(new FavoriteComplexId(userId, complexId)).map(JpaFavoriteComplexRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public FavoritePage list(long userId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(
            Sort.Order.desc("savedAt"), Sort.Order.desc("id.complexId")));
        var result = favorites.findByIdUserId(userId, pageable);
        return new FavoritePage(result.getContent().stream().map(JpaFavoriteComplexRepository::toDomain).toList(), result.getTotalElements());
    }

    private static FavoriteComplex toDomain(FavoriteComplexJpaEntity entity) {
        return new FavoriteComplex(entity.userId(), entity.complexId(), entity.savedAt());
    }
}
