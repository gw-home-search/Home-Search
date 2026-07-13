package com.home.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PersistenceEntityMappingTest {

    @Test
    void favoriteComplexIdKeepsCompositeIdentitySemantics() {
        FavoriteComplexId first = new FavoriteComplexId(10L, 20L);
        FavoriteComplexId same = new FavoriteComplexId(10L, 20L);
        FavoriteComplexId other = new FavoriteComplexId(10L, 21L);

        assertThat(first.userId()).isEqualTo(10L);
        assertThat(first.complexId()).isEqualTo(20L);
        assertThat(first).isEqualTo(first).isEqualTo(same).isNotEqualTo(other).isNotEqualTo("10:20");
        assertThat(first.hashCode()).isEqualTo(same.hashCode()).isNotEqualTo(other.hashCode());
        assertThat(new FavoriteComplexId()).isNotEqualTo(first);
    }

    @Test
    void refreshTokenEntityReplacesActiveStateWithoutChangingOwner() {
        Instant issuedAt = Instant.parse("2026-07-13T00:00:00Z");
        Instant expiresAt = issuedAt.plusSeconds(3600);
        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity(42L, "a".repeat(64), issuedAt, expiresAt);

        assertThat(entity.userId()).isEqualTo(42L);
        assertThat(entity.tokenHash()).isEqualTo("a".repeat(64));
        assertThat(entity.issuedAt()).isEqualTo(issuedAt);
        assertThat(entity.expiresAt()).isEqualTo(expiresAt);

        Instant rotatedAt = issuedAt.plusSeconds(60);
        Instant rotatedExpiry = expiresAt.plusSeconds(60);
        entity.replace("b".repeat(64), rotatedAt, rotatedExpiry);

        assertThat(entity.userId()).isEqualTo(42L);
        assertThat(entity.tokenHash()).isEqualTo("b".repeat(64));
        assertThat(entity.issuedAt()).isEqualTo(rotatedAt);
        assertThat(entity.expiresAt()).isEqualTo(rotatedExpiry);
        assertThat(new RefreshTokenJpaEntity()).isNotNull();
    }
}
