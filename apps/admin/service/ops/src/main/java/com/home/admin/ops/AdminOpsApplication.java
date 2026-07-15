package com.home.admin.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class AdminOpsApplication {
    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(AdminOpsApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        System.exit(SpringApplication.exit(context));
    }
}
