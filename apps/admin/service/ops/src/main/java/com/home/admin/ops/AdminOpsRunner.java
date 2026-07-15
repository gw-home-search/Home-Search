package com.home.admin.ops;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class AdminOpsRunner implements ApplicationRunner, ExitCodeGenerator {
    static final String EXPECTED_DATABASE = "home_search_admin";
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final AdminPasswordSource passwordSource;
    private int exitCode;

    AdminOpsRunner(
            DataSource dataSource,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            AdminPasswordSource passwordSource) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.passwordSource = passwordSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            Map<String, String> options = parse(arguments.getSourceArgs());
            requireDatabase();
            String operation = required(options, "operation");
            transaction.executeWithoutResult(status -> execute(operation, options));
        } catch (UsageException exception) {
            System.err.println(exception.getMessage());
            exitCode = 2;
        } catch (Exception exception) {
            System.err.println("admin ops failed: " + exception.getMessage());
            exitCode = 1;
        }
    }

    private void execute(String operation, Map<String, String> options) {
        switch (operation) {
            case "create-initial-admin" -> createInitialAdmin(options);
            case "create-account" -> createAccount(options, required(options, "role"));
            case "set-password" -> setPassword(options);
            case "grant-role" -> grantRole(options);
            case "disable-account" -> disableAccount(options);
            case "revoke-sessions" -> revokeSessions(required(options, "login-id"));
            default -> throw new UsageException("unsupported operation: " + operation);
        }
    }

    private void createInitialAdmin(Map<String, String> options) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('home_search_admin_initial_account'))")
                .query((result, rowNumber) -> Boolean.TRUE)
                .single();
        int active = jdbc.sql("SELECT count(*) FROM admin.admin_account WHERE enabled")
                .query(Integer.class)
                .single();
        if (active != 0) throw new UsageException("create-initial-admin requires zero active accounts");
        createAccount(options, "ADMIN");
    }

    private void createAccount(Map<String, String> options, String role) {
        requireKnownRole(role);
        String loginId = required(options, "login-id");
        String displayName = required(options, "display-name");
        String hash = new BCryptPasswordEncoder(12).encode(passwordSource.read());
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO admin.admin_account(id,login_id,display_name,password_hash) VALUES (:id,:loginId,:displayName,:hash)")
                .param("id", id)
                .param("loginId", loginId)
                .param("displayName", displayName)
                .param("hash", hash)
                .update();
        jdbc.sql("INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role)")
                .param("id", id)
                .param("role", role)
                .update();
        audit(id, "ACCOUNT_CREATED");
        System.out.println("admin account created: loginId=" + loginId + ", role=" + role);
    }

    private void setPassword(Map<String, String> options) {
        String loginId = required(options, "login-id");
        String hash = new BCryptPasswordEncoder(12).encode(passwordSource.read());
        int updated = jdbc.sql(
                        "UPDATE admin.admin_account SET password_hash=:hash, failed_login_count=0, locked_until=NULL, updated_at=now() WHERE login_id=:loginId")
                .param("hash", hash)
                .param("loginId", loginId)
                .update();
        requireUpdated(updated, loginId);
        revokeSessions(loginId);
        audit(accountId(loginId), "PASSWORD_CHANGED");
    }

    private void grantRole(Map<String, String> options) {
        String loginId = required(options, "login-id");
        String role = required(options, "role");
        requireKnownRole(role);
        UUID id = accountId(loginId);
        jdbc.sql("INSERT INTO admin.admin_account_role(account_id,role_code) VALUES (:id,:role) ON CONFLICT DO NOTHING")
                .param("id", id)
                .param("role", role)
                .update();
        revokeSessions(loginId);
        audit(id, "ROLE_GRANTED");
    }

    private void disableAccount(Map<String, String> options) {
        String loginId = required(options, "login-id");
        UUID id = accountId(loginId);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('home_search_admin_membership_policy'))")
                .query((result, rowNumber) -> Boolean.TRUE)
                .single();
        boolean lastActiveAdmin =
                jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM admin.admin_account a JOIN admin.admin_account_role ar ON ar.account_id=a.id
                          WHERE a.id=:id AND a.enabled AND ar.role_code='ADMIN')
               AND NOT EXISTS(SELECT 1 FROM admin.admin_account a JOIN admin.admin_account_role ar ON ar.account_id=a.id
                              WHERE a.id<>:id AND a.enabled AND ar.role_code='ADMIN')
            """).param("id", id).query(Boolean.class).single();
        if (lastActiveAdmin) throw new UsageException("last active ADMIN cannot be disabled");
        int updated = jdbc.sql("UPDATE admin.admin_account SET enabled=false, updated_at=now() WHERE id=:id")
                .param("id", id)
                .update();
        requireUpdated(updated, loginId);
        revokeSessions(loginId);
        audit(id, "ACCOUNT_DISABLED");
    }

    private void revokeSessions(String loginId) {
        jdbc.sql("DELETE FROM admin.spring_session WHERE principal_name=:loginId")
                .param("loginId", loginId)
                .update();
    }

    private void audit(UUID accountId, String type) {
        jdbc.sql(
                        "INSERT INTO admin.admin_security_audit_event(target_account_id,event_type,success) VALUES (:id,:type,true)")
                .param("id", accountId)
                .param("type", type)
                .update();
    }

    private UUID accountId(String loginId) {
        return jdbc.sql("SELECT id FROM admin.admin_account WHERE login_id=:loginId")
                .param("loginId", loginId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new UsageException("admin account not found: " + loginId));
    }

    private void requireKnownRole(String role) {
        if (!java.util.Set.of("VIEWER", "OPERATOR", "ADMIN").contains(role))
            throw new UsageException("unknown role: " + role);
    }

    private void requireUpdated(int updated, String loginId) {
        if (updated != 1) throw new UsageException("admin account not found: " + loginId);
    }

    private void requireDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT current_database()");
                var result = statement.executeQuery()) {
            result.next();
            if (!EXPECTED_DATABASE.equals(result.getString(1)))
                throw new UsageException("target database must be " + EXPECTED_DATABASE);
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = Arrays.stream(args)
                .map(value -> value.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].startsWith("--"))
                .collect(Collectors.toUnmodifiableMap(
                        parts -> parts[0].substring(2), parts -> parts[1], (left, right) -> {
                            throw new UsageException("duplicate option");
                        }));
        if (options.containsKey("password")) throw new UsageException("password command arguments are forbidden");
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new UsageException("missing --" + key);
        return value;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
