package com.home.infrastructure.persistence.user;

import static org.assertj.core.api.Assertions.assertThat;

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
}
