package com.home.admin.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountService {
    private static final Set<String> ROLES = Set.of("VIEWER", "OPERATOR", "ADMIN");
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountService(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public List<AccountSummary> accounts() {
        return jdbc.sql("""
            SELECT a.id,a.login_id,a.display_name,a.enabled,a.locked_until,
                   COALESCE(array_agg(ar.role_code ORDER BY ar.role_code) FILTER (WHERE ar.role_code IS NOT NULL), ARRAY[]::varchar[]) roles
            FROM admin.admin_account a LEFT JOIN admin.admin_account_role ar ON ar.account_id=a.id
            GROUP BY a.id ORDER BY a.login_id
            """)
                .query((rs, row) -> new AccountSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("login_id"),
                        rs.getString("display_name"),
                        rs.getBoolean("enabled"),
                        rs.getObject("locked_until", OffsetDateTime.class),
                        Set.of((String[]) rs.getArray("roles").getArray())))
                .list();
    }

    @Transactional
    public AccountSummary create(UUID actorId, CreateAccount command) {
        requireRoles(command.roles());
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO admin.admin_account(id,login_id,display_name,password_hash) VALUES (:id,:loginId,:displayName,:hash)")
                .param("id", id)
                .param("loginId", command.loginId())
                .param("displayName", command.displayName())
                .param("hash", passwordEncoder.encode(command.password()))
                .update();
        command.roles()
                .forEach(role -> jdbc.sql(
                                "INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role)")
                        .param("id", id)
                        .param("role", role)
                        .update());
        audit(actorId, id, "ACCOUNT_CREATED");
        return new AccountSummary(
                id, command.loginId(), command.displayName(), true, null, Set.copyOf(command.roles()));
    }

    @Transactional
    public void replaceRoles(UUID actorId, UUID accountId, Set<String> roles) {
        requireRoles(roles);
        requireAccount(accountId);
        if (!roles.contains("ADMIN")) {
            lockAdminMembershipPolicy();
            ensureNotLastActiveAdmin(accountId);
        }
        jdbc.sql("DELETE FROM admin.admin_account_role WHERE account_id=:id")
                .param("id", accountId)
                .update();
        roles.forEach(role -> jdbc.sql("INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role)")
                .param("id", accountId)
                .param("role", role)
                .update());
        revokeSessionsFor(accountId);
        audit(actorId, accountId, "ROLES_CHANGED");
    }

    @Transactional
    public void setEnabled(UUID actorId, UUID accountId, boolean enabled) {
        if (!enabled) {
            lockAdminMembershipPolicy();
            ensureNotLastActiveAdmin(accountId);
        }
        int updated = jdbc.sql("UPDATE admin.admin_account SET enabled=:enabled,updated_at=now() WHERE id=:id")
                .param("enabled", enabled)
                .param("id", accountId)
                .update();
        if (updated != 1) throw new AccountNotFoundException();
        revokeSessionsFor(accountId);
        audit(actorId, accountId, enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED");
    }

    @Transactional
    public void revokeSessions(UUID actorId, UUID accountId) {
        requireAccount(accountId);
        revokeSessionsFor(accountId);
        audit(actorId, accountId, "SESSIONS_REVOKED");
    }

    private void revokeSessionsFor(UUID accountId) {
        jdbc.sql(
                        "DELETE FROM admin.spring_session WHERE principal_name=(SELECT login_id FROM admin.admin_account WHERE id=:id)")
                .param("id", accountId)
                .update();
    }

    private void requireAccount(UUID id) {
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM admin.admin_account WHERE id=:id)")
                .param("id", id)
                .query(Boolean.class)
                .single()) throw new AccountNotFoundException();
    }

    private void requireRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty() || !ROLES.containsAll(roles)) throw new InvalidRoleException();
    }

    private void ensureNotLastActiveAdmin(UUID accountId) {
        boolean targetIsActiveAdmin =
                jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM admin.admin_account a JOIN admin.admin_account_role ar ON ar.account_id=a.id
                          WHERE a.id=:id AND a.enabled AND ar.role_code='ADMIN')
            """).param("id", accountId).query(Boolean.class).single();
        if (!targetIsActiveAdmin) return;
        boolean anotherActiveAdmin =
                jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM admin.admin_account a JOIN admin.admin_account_role ar ON ar.account_id=a.id
                          WHERE a.id<>:id AND a.enabled AND ar.role_code='ADMIN')
            """).param("id", accountId).query(Boolean.class).single();
        if (!anotherActiveAdmin) throw new CannotRemoveLastAdminException();
    }

    private void lockAdminMembershipPolicy() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('home_search_admin_membership_policy'))")
                .query((result, rowNumber) -> Boolean.TRUE)
                .single();
    }

    private void audit(UUID actor, UUID target, String type) {
        jdbc.sql(
                        "INSERT INTO admin.admin_security_audit_event(actor_account_id,target_account_id,event_type,success) VALUES (:actor,:target,:type,true)")
                .param("actor", actor)
                .param("target", target)
                .param("type", type)
                .update();
    }

    public record CreateAccount(String loginId, String displayName, String password, Set<String> roles) {}

    public record AccountSummary(
            UUID accountId,
            String loginId,
            String displayName,
            boolean enabled,
            OffsetDateTime lockedUntil,
            Set<String> roles) {}

    public static final class AccountNotFoundException extends RuntimeException {}

    public static final class InvalidRoleException extends RuntimeException {}

    public static final class CannotRemoveLastAdminException extends RuntimeException {}
}
