package com.home.infrastructure.persistence.user;

import com.home.application.auth.port.RefreshTokenRepository;
import com.home.domain.user.token.ActiveRefreshToken;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {
    private static final String REPLACE_ACTIVE = """
            INSERT INTO users.refresh_token(
                user_id, token_hash, issued_at, expires_at, updated_at, revoked_at, version
            ) VALUES (
                :userId, :hash, :issuedAt, :expiresAt, :issuedAt, NULL, 0
            )
            ON CONFLICT(user_id) DO UPDATE SET
                token_hash = excluded.token_hash,
                issued_at = excluded.issued_at,
                expires_at = excluded.expires_at,
                updated_at = excluded.updated_at,
                revoked_at = NULL,
                version = users.refresh_token.version + 1
            """;
    private static final String FIND_ACTIVE = """
            SELECT user_id, trim(token_hash) AS token_hash, issued_at, expires_at
            FROM users.refresh_token
            WHERE token_hash = :hash
              AND revoked_at IS NULL
            """;
    private static final String ROTATE_ACTIVE = """
            UPDATE users.refresh_token
            SET token_hash = :newHash,
                issued_at = :now,
                expires_at = :expiresAt,
                updated_at = :now,
                revoked_at = NULL,
                version = version + 1
            WHERE token_hash = :oldHash
              AND revoked_at IS NULL
              AND expires_at > :now
            """;
    private static final String REVOKE = """
            UPDATE users.refresh_token
            SET revoked_at = :now,
                updated_at = :now,
                version = version + 1
            WHERE token_hash = :hash
              AND revoked_at IS NULL
            """;

    private final JdbcClient jdbc;

    public JdbcRefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceActive(ActiveRefreshToken token) {
        jdbc.sql(REPLACE_ACTIVE)
                .param("userId", token.userId())
                .param("hash", token.tokenHash())
                .param("issuedAt", Timestamp.from(token.issuedAt()))
                .param("expiresAt", Timestamp.from(token.expiresAt()))
                .update();
    }

    @Override
    public Optional<ActiveRefreshToken> findActiveByHash(String hash) {
        return jdbc.sql(FIND_ACTIVE)
                .param("hash", hash)
                .query((resultSet, rowNumber) -> new ActiveRefreshToken(
                        resultSet.getLong("user_id"),
                        resultSet.getString("token_hash"),
                        resultSet.getTimestamp("issued_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public boolean rotateActive(String expectedHash, ActiveRefreshToken replacement, Instant now) {
        return jdbc.sql(ROTATE_ACTIVE)
                        .param("oldHash", expectedHash)
                        .param("newHash", replacement.tokenHash())
                        .param("now", Timestamp.from(now))
                        .param("expiresAt", Timestamp.from(replacement.expiresAt()))
                        .update()
                == 1;
    }

    @Override
    public void revokeByHash(String hash, Instant now) {
        jdbc.sql(REVOKE).param("hash", hash).param("now", Timestamp.from(now)).update();
    }
}
