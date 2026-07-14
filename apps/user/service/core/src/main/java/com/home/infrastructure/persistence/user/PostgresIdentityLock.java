package com.home.infrastructure.persistence.user;

import com.home.application.user.port.IdentityLock;
import com.home.domain.user.OAuthIdentityKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PostgresIdentityLock implements IdentityLock {
    private final JdbcClient jdbc;

    public PostgresIdentityLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(OAuthIdentityKey identity) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", identity.provider().name() + ":" + identity.providerSubject())
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }
}
