package com.home.application.news.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.home.application.news.collection.MarketNewsPublicationCache;
import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWithdrawalReason;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsQualityServiceTest {

    private static final UUID SNAPSHOT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174720");

    @Test
    @DisplayName("발행 snapshot을 철회하고 현재 cache pointer만 교체한다")
    void withdrawsPublishedSnapshotAndRemovesOnlyCurrentCachePointer() {
        MarketNewsQualityRepository repository = mock(MarketNewsQualityRepository.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        PublishedNewsSnapshot lastGood = new PublishedNewsSnapshot(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174721"),
                MarketNewsScopeType.SIDO,
                "11",
                java.time.Instant.parse("2026-07-24T09:00:00Z"),
                java.time.Instant.parse("2026-07-24T08:59:00Z"));
        WithdrawnNewsSnapshot withdrawn =
                new WithdrawnNewsSnapshot(SNAPSHOT_ID, MarketNewsScopeType.SIDO, "11", lastGood);
        when(repository.withdrawPublished(SNAPSHOT_ID, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD))
                .thenReturn(Optional.of(withdrawn));

        assertThat(new MarketNewsQualityService(repository, cache)
                        .withdraw(SNAPSHOT_ID, MarketNewsWithdrawalReason.RELATION_ACCURACY_BELOW_THRESHOLD))
                .isEqualTo(withdrawn);
        verify(cache).withdraw(MarketNewsScopeType.SIDO, "11", lastGood);
    }

    @Test
    @DisplayName("발행 snapshot이 없으면 cache를 변경하지 않는다")
    void doesNotChangeCacheWhenPublishedSnapshotIsMissing() {
        MarketNewsQualityRepository repository = mock(MarketNewsQualityRepository.class);
        MarketNewsPublicationCache cache = mock(MarketNewsPublicationCache.class);
        when(repository.withdrawPublished(SNAPSHOT_ID, MarketNewsWithdrawalReason.UNSAFE_PUBLIC_ITEM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new MarketNewsQualityService(repository, cache)
                        .withdraw(SNAPSHOT_ID, MarketNewsWithdrawalReason.UNSAFE_PUBLIC_ITEM))
                .isInstanceOf(IllegalStateException.class);
    }
}
