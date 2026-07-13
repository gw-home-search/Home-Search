package com.home.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.main.web-application-type=none")
class UserServiceApplicationTest {
    @Test
    void startsWithoutDatabaseOrMigrationCredentials() {}
}
