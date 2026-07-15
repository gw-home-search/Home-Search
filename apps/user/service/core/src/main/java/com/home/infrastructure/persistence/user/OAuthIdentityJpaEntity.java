package com.home.infrastructure.persistence.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(schema = "users", name = "oauth_identity")
public class OAuthIdentityJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected OAuthIdentityJpaEntity() {}

    public OAuthIdentityJpaEntity(long userId, String provider, String subject, Instant now) {
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = subject;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public void loginAt(Instant now) {
        this.lastLoginAt = now;
    }

    public long userId() {
        return userId;
    }

    public String provider() {
        return provider;
    }
}
