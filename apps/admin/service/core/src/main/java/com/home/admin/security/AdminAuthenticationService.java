package com.home.admin.security;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthenticationService {
    private static final String DUMMY_HASH = "$2a$12$2b2tqUaD5Oq9d5K7zUwTeuYn6hZEVAh8zZeWwhP9lvtI1Z0Olflym";
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public AdminAuthenticationService(JdbcClient jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AdminPrincipal authenticate(String loginId, String password) {
        Account account = jdbc.sql("SELECT id, login_id, display_name, password_hash, enabled, failed_login_count, locked_until FROM admin.admin_account WHERE login_id = :loginId")
            .param("loginId", loginId).query(this::account).optional().orElse(null);
        if (account == null) {
            encoder.matches(password, DUMMY_HASH);
            audit(null, "LOGIN_FAILURE", false);
            throw new InvalidCredentialsException();
        }
        if (!account.enabled || account.lockedUntil != null && account.lockedUntil.isAfter(OffsetDateTime.now())
            || !encoder.matches(password, account.passwordHash)) {
            recordFailure(account);
            audit(account.id, "LOGIN_FAILURE", false);
            throw new InvalidCredentialsException();
        }
        jdbc.sql("UPDATE admin.admin_account SET failed_login_count=0, locked_until=NULL, updated_at=now() WHERE id=:id")
            .param("id", account.id).update();
        Set<String> roles = new HashSet<>(jdbc.sql("SELECT role_code FROM admin.admin_account_role WHERE account_id=:id")
            .param("id", account.id).query(String.class).list());
        Set<String> permissions = new HashSet<>(jdbc.sql("SELECT DISTINCT rp.permission_code FROM admin.admin_account_role ar JOIN admin.admin_role_permission rp ON rp.role_code=ar.role_code WHERE ar.account_id=:id")
            .param("id", account.id).query(String.class).list());
        audit(account.id, "LOGIN_SUCCESS", true);
        return new AdminPrincipal(account.id, account.loginId, account.displayName, Set.copyOf(roles), Set.copyOf(permissions));
    }

    private void recordFailure(Account account) {
        jdbc.sql("UPDATE admin.admin_account SET failed_login_count=failed_login_count+1, locked_until=CASE WHEN failed_login_count+1 >= 5 THEN now()+interval '15 minutes' ELSE locked_until END, updated_at=now() WHERE id=:id")
            .param("id", account.id).update();
    }
    private void audit(UUID accountId, String type, boolean success) {
        jdbc.sql("INSERT INTO admin.admin_security_audit_event(target_account_id,event_type,success) VALUES (:id,:type,:success)")
            .param("id", accountId).param("type", type).param("success", success).update();
    }
    private Account account(ResultSet rs, int row) throws java.sql.SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("login_id"), rs.getString("display_name"),
            rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getInt("failed_login_count"),
            rs.getObject("locked_until", OffsetDateTime.class));
    }
    private record Account(UUID id, String loginId, String displayName, String passwordHash, boolean enabled,
                           int failures, OffsetDateTime lockedUntil) {}
    public static final class InvalidCredentialsException extends RuntimeException {}
}
