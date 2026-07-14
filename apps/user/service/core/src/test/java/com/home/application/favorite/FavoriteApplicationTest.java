package com.home.application.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteComplex;
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
        FavoriteService service = new FavoriteService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        service.save(7, 501);
        service.save(7, 501);
        service.save(7, 502);

        assertThat(service.get(7, 501)).contains(new FavoriteComplex(7, 501, NOW));
        var page = service.list(7, 0, 20);
        assertThat(page.content()).extracting(FavoriteComplex::complexId).containsExactly(502L, 501L);
        assertThat(page.totalElements()).isEqualTo(2);

        service.remove(7, 501);
        service.remove(7, 501);
        assertThat(service.get(7, 501)).isEmpty();
    }

    @Test
    void validatesIdsAndEnforcesLimitWithoutRejectingExistingFavorite() {
        assertThatThrownBy(() -> new FavoriteComplex(0, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FavoriteComplex(1, 0, NOW)).isInstanceOf(IllegalArgumentException.class);
        FakeRepository repository = new FakeRepository();
        for (int index = 1; index <= 200; index++) {
            repository.values.add(new FavoriteComplex(7, index, NOW));
        }
        FavoriteService service = new FavoriteService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.save(7, 201)).isInstanceOf(FavoriteLimitReachedException.class);
        assertThat(service.save(7, 200)).isEqualTo(new FavoriteComplex(7, 200, NOW));
    }

    private static final class FakeRepository implements FavoriteComplexRepository {
        private final List<FavoriteComplex> values = new ArrayList<>();

        @Override
        public boolean lockUser(long userId) {
            return userId == 7;
        }

        @Override
        public Optional<FavoriteComplex> find(long userId, long complexId) {
            return values.stream()
                    .filter(value -> value.userId() == userId && value.complexId() == complexId)
                    .findFirst();
        }

        @Override
        public long count(long userId) {
            return values.stream().filter(value -> value.userId() == userId).count();
        }

        @Override
        public FavoriteComplex save(FavoriteComplex favorite) {
            values.add(favorite);
            return favorite;
        }

        @Override
        public void remove(long userId, long complexId) {
            values.removeIf(value -> value.userId() == userId && value.complexId() == complexId);
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
