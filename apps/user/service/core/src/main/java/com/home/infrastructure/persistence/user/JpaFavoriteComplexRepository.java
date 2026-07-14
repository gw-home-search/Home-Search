package com.home.infrastructure.persistence.user;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteComplex;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class JpaFavoriteComplexRepository implements FavoriteComplexRepository {
    private final SpringDataFavoriteComplexRepository favorites;

    public JpaFavoriteComplexRepository(SpringDataFavoriteComplexRepository favorites) {
        this.favorites = favorites;
    }

    @Override
    public boolean lockUser(long userId) {
        return favorites.lockUser(userId).isPresent();
    }

    @Override
    public Optional<FavoriteComplex> find(long userId, long complexId) {
        return favorites.findById(new FavoriteComplexId(userId, complexId)).map(JpaFavoriteComplexRepository::toDomain);
    }

    @Override
    public long count(long userId) {
        return favorites.countByIdUserId(userId);
    }

    @Override
    public FavoriteComplex save(FavoriteComplex favorite) {
        return toDomain(favorites.save(
                new FavoriteComplexJpaEntity(favorite.userId(), favorite.complexId(), favorite.savedAt())));
    }

    @Override
    public void remove(long userId, long complexId) {
        favorites.deleteByIdUserIdAndIdComplexId(userId, complexId);
    }

    @Override
    public FavoritePage list(long userId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("id.complexId")));
        var result = favorites.findByIdUserId(userId, pageable);
        return new FavoritePage(
                result.getContent().stream()
                        .map(JpaFavoriteComplexRepository::toDomain)
                        .toList(),
                result.getTotalElements());
    }

    private static FavoriteComplex toDomain(FavoriteComplexJpaEntity entity) {
        return new FavoriteComplex(entity.userId(), entity.complexId(), entity.savedAt());
    }
}
