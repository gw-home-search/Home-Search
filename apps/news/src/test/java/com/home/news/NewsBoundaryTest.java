package com.home.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsBoundaryTest {

	@Test
	@DisplayName("news app은 later-scope runtime 경계를 가진다")
	void ownsLaterScopeRuntimeBoundary() {
		assertThat(NewsBoundary.APP_NAME).isEqualTo("home-search-news");
		assertThat(NewsBoundary.SCOPE).isEqualTo("later-scope");
	}
}
