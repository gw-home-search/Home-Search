package com.home.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class UserServiceApplicationTest {
    @Test
    void isAnIndependentSpringBootApplication() {
        assertThat(UserServiceApplication.class).hasAnnotation(SpringBootApplication.class);
    }
}
