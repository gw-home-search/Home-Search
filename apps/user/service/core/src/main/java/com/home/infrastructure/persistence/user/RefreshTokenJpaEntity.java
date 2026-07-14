package com.home.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(schema = "users", name = "refresh_token")
public class RefreshTokenJpaEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(nullable = false)
    private Long version;

    protected RefreshTokenJpaEntity() {}

    public RefreshTokenJpaEntity(long userId, String hash, Instant issued, Instant expires) {
        this.userId = userId;
        this.tokenHash = hash;
        this.issuedAt = issued;
        this.expiresAt = expires;
        this.updatedAt = issued;
        this.version = 0L;
    }

    public void replace(String hash, Instant issued, Instant expires) {
        this.tokenHash = hash;
        this.issuedAt = issued;
        this.expiresAt = expires;
        this.updatedAt = issued;
        this.revokedAt = null;
        this.version++;
    }

    public long userId() {
        return userId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
