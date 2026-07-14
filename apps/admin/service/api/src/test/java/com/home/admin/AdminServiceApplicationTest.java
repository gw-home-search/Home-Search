package com.home.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

class AdminServiceApplicationTest {
    @Test
    void excludesUnusedGeneratedUserPasswordAutoConfiguration() {
        SpringBootApplication application = AdminServiceApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
    }
}
