package com.home.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.home")
@ConfigurationPropertiesScan(basePackages = "com.home.user.config.properties")
@EntityScan(basePackages = "com.home.infrastructure.persistence.user")
@EnableJpaRepositories(basePackages = "com.home.infrastructure.persistence.user")
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
