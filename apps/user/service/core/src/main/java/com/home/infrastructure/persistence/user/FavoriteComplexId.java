package com.home.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class FavoriteComplexId implements Serializable {
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "complex_id", nullable = false) private Long complexId;
    protected FavoriteComplexId() { }
    public FavoriteComplexId(long userId, long complexId) { this.userId = userId; this.complexId = complexId; }
    public long userId() { return userId; }
    public long complexId() { return complexId; }
    @Override public boolean equals(Object other) { return other instanceof FavoriteComplexId id && Objects.equals(userId, id.userId) && Objects.equals(complexId, id.complexId); }
    @Override public int hashCode() { return Objects.hash(userId, complexId); }
}
