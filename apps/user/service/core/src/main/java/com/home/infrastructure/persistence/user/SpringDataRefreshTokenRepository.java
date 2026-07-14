package com.home.infrastructure.persistence.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataRefreshTokenRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {
    @Modifying
    @Query(
            value =
                    "INSERT INTO users.refresh_token(user_id,token_hash,issued_at,expires_at,updated_at,revoked_at,version) VALUES(:userId,:hash,:issuedAt,:expiresAt,:issuedAt,NULL,0) ON CONFLICT(user_id) DO UPDATE SET token_hash=excluded.token_hash,issued_at=excluded.issued_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at,revoked_at=NULL,version=users.refresh_token.version+1",
            nativeQuery = true)
    int replaceActive(
            @Param("userId") long userId,
            @Param("hash") String hash,
            @Param("issuedAt") Instant issuedAt,
            @Param("expiresAt") Instant expiresAt);

    @Query("select token from RefreshTokenJpaEntity token where token.tokenHash=:hash and token.revokedAt is null")
    Optional<RefreshTokenJpaEntity> findActiveByHash(@Param("hash") String hash);

    @Modifying
    @Query(
            value =
                    "UPDATE users.refresh_token SET token_hash=:newHash,issued_at=:now,expires_at=:expiresAt,updated_at=:now,revoked_at=NULL,version=version+1 WHERE token_hash=:oldHash AND revoked_at IS NULL AND expires_at>:now",
            nativeQuery = true)
    int rotate(
            @Param("oldHash") String oldHash,
            @Param("newHash") String newHash,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(
            value =
                    "UPDATE users.refresh_token SET revoked_at=:now,updated_at=:now,version=version+1 WHERE token_hash=:hash AND revoked_at IS NULL",
            nativeQuery = true)
    int revoke(@Param("hash") String hash, @Param("now") Instant now);
}
