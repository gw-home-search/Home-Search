package com.home.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminSecurityPolicyTest {
    @Test void bcryptCostIsAtLeastTwelve() {
        String encoded = new BCryptPasswordEncoder(12).encode("test-password");
        assertThat(encoded).startsWith("$2");
        assertThat(Integer.parseInt(encoded.substring(4, 6))).isGreaterThanOrEqualTo(12);
    }

    @Test void sessionPrincipalNameIsTheBoundedLoginId() {
        var principal = new AdminPrincipal(UUID.randomUUID(), "operator", "운영자",
            Set.of("ADMIN"), Set.of("ADMIN_ACCOUNT_MANAGE", "ADMIN_AUDIT_READ"));

        assertThat(principal).isInstanceOf(Principal.class);
        assertThat(((Principal) principal).getName()).isEqualTo("operator");
    }
}
