package com.home.application.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteComplex;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import com.home.domain.user.favorite.FavoriteLimitReachedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FavoriteApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-13T06:00:00Z");

    @Test
    void savesIdempotentlyListsAndRemoves() {
        FakeRepository repository = new FakeRepository();
        SaveFavoriteComplex save =
                new SaveFavoriteComplex(repository, new FavoriteLimitPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
        save.execute(7, 501);
        save.execute(7, 501);
        save.execute(7, 502);

        assertThat(new GetFavoriteComplex(repository).execute(7, 501)).contains(new FavoriteComplex(7, 501, NOW));
        var page = new ListFavoriteComplexes(repository).execute(7, 0, 20);
        assertThat(page.content()).extracting(FavoriteComplex::complexId).containsExactly(502L, 501L);
        assertThat(page.totalElements()).isEqualTo(2);

        RemoveFavoriteComplex remove = new RemoveFavoriteComplex(repository);
        remove.execute(7, 501);
        remove.execute(7, 501);
        assertThat(new GetFavoriteComplex(repository).execute(7, 501)).isEmpty();
    }

    @Test
    void validatesIdsAndEnforcesLimitWithoutRejectingExistingFavorite() {
        FavoriteLimitPolicy policy = new FavoriteLimitPolicy();
        assertThatThrownBy(() -> new FavoriteComplex(0, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FavoriteComplex(1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.ensureCanSave(false, 200)).isInstanceOf(FavoriteLimitReachedException.class);
        policy.ensureCanSave(true, 200);
    }

    private static final class FakeRepository implements FavoriteComplexRepository {
        private final List<FavoriteComplex> values = new ArrayList<>();

        @Override
        public FavoriteComplex save(long userId, long complexId, Instant savedAt, FavoriteLimitPolicy policy) {
            Optional<FavoriteComplex> existing = get(userId, complexId);
            policy.ensureCanSave(
                    existing.isPresent(),
                    values.stream().filter(value -> value.userId() == userId).count());
            if (existing.isPresent()) return existing.get();
            FavoriteComplex favorite = new FavoriteComplex(userId, complexId, savedAt);
            values.add(favorite);
            return favorite;
        }

        @Override
        public void remove(long userId, long complexId) {
            values.removeIf(value -> value.userId() == userId && value.complexId() == complexId);
        }

        @Override
        public Optional<FavoriteComplex> get(long userId, long complexId) {
            return values.stream()
                    .filter(value -> value.userId() == userId && value.complexId() == complexId)
                    .findFirst();
        }

        @Override
        public FavoritePage list(long userId, int page, int size) {
            List<FavoriteComplex> all = values.stream()
                    .filter(value -> value.userId() == userId)
                    .sorted(Comparator.comparing(FavoriteComplex::savedAt)
                            .reversed()
                            .thenComparing(FavoriteComplex::complexId, Comparator.reverseOrder()))
                    .toList();
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            return new FavoritePage(all.subList(from, to), all.size());
        }
    }
}
