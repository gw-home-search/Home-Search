package com.home.sourcedata;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SourceDataApplication {

	public static void main(String[] args) {
		var context = new SpringApplicationBuilder(SourceDataApplication.class)
			.web(WebApplicationType.NONE)
			.run(args);
		System.exit(SpringApplication.exit(context));
	}
}
