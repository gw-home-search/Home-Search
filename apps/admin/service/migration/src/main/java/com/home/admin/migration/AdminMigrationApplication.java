package com.home.admin.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class AdminMigrationApplication {
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(AdminMigrationApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        System.exit(SpringApplication.exit(context));
    }
}
