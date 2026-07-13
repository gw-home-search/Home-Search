package com.home.user;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.autoconfigure.SpringBootApplication;

class UserServiceApplicationTest {
    @Test
    void isAnIndependentSpringBootApplication() {
        assertThat(UserServiceApplication.class).hasAnnotation(SpringBootApplication.class);
    }
}
