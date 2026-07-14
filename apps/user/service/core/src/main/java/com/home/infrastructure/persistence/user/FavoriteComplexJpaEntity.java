package com.home.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(schema = "users", name = "favorite_complex")
public class FavoriteComplexJpaEntity {
    @EmbeddedId
    private FavoriteComplexId id;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private Instant savedAt;

    protected FavoriteComplexJpaEntity() {}

    public FavoriteComplexJpaEntity(long userId, long complexId, Instant savedAt) {
        this.id = new FavoriteComplexId(userId, complexId);
        this.savedAt = savedAt;
    }

    public long userId() {
        return id.userId();
    }

    public long complexId() {
        return id.complexId();
    }

    public Instant savedAt() {
        return savedAt;
    }
}
