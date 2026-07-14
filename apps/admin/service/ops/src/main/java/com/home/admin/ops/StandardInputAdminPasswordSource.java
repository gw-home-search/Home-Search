package com.home.admin.ops;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.stereotype.Component;

@Component
final class StandardInputAdminPasswordSource implements AdminPasswordSource {
    @Override
    public String read() {
        if (System.getenv().containsKey("ADMIN_OPS_PASSWORD")) {
            String value = System.getenv("ADMIN_OPS_PASSWORD");
            if (value != null && !value.isBlank()) return value;
        }
        try {
            char[] consoleValue =
                    System.console() == null ? null : System.console().readPassword("Password: ");
            String value = consoleValue == null
                    ? new BufferedReader(new InputStreamReader(System.in)).readLine()
                    : new String(consoleValue);
            if (value == null || value.isBlank())
                throw new AdminOpsRunner.UsageException(
                        "password must be provided through stdin or ADMIN_OPS_PASSWORD");
            return value;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("could not read password", exception);
        }
    }
}
