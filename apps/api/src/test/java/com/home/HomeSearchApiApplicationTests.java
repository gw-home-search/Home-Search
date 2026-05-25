package com.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class HomeSearchApiApplicationTests {

	@Test
	@DisplayName("Spring Boot context는 test profile로 load된다")
	void contextLoads() {
	}
}
