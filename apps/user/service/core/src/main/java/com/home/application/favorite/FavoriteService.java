package com.home.application.favorite;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.application.favorite.port.FavoriteComplexRepository.FavoritePage;
import com.home.application.user.UserNotFoundException;
import com.home.domain.user.favorite.FavoriteComplex;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
    private final FavoriteComplexRepository repository;
    private final FavoriteLimitPolicy limitPolicy = new FavoriteLimitPolicy();
    private final Clock clock;

    public FavoriteService(FavoriteComplexRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public FavoriteComplex save(long userId, long complexId) {
        validate(userId, complexId);
        if (!repository.lockUser(userId)) throw new UserNotFoundException();
        Optional<FavoriteComplex> existing = repository.find(userId, complexId);
        limitPolicy.ensureCanSave(existing.isPresent(), existing.isPresent() ? 0 : repository.count(userId));
        return existing.orElseGet(() -> repository.save(new FavoriteComplex(userId, complexId, clock.instant())));
    }

    @Transactional
    public void remove(long userId, long complexId) {
        validate(userId, complexId);
        repository.remove(userId, complexId);
    }

    @Transactional(readOnly = true)
    public Optional<FavoriteComplex> get(long userId, long complexId) {
        validate(userId, complexId);
        return repository.find(userId, complexId);
    }

    @Transactional(readOnly = true)
    public FavoritePage list(long userId, int page, int size) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (page < 0 || size < 1 || size > 100) throw new InvalidPaginationException();
        return repository.list(userId, page, size);
    }

    private static void validate(long userId, long complexId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        if (complexId <= 0) throw new InvalidComplexIdException();
    }
}
