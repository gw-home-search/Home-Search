package com.home;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.home.application.map.MapUseCase;

@SpringBootTest
@ActiveProfiles("test")
class HomeSearchApiApplicationTests {

	@MockitoBean
	private MapUseCase mapUseCase;

	@Test
	@DisplayName("Spring Boot context는 test profile로 load된다")
	void contextLoads() {
	}
}
