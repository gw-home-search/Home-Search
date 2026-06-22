package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.news.NewsRegionBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionAliasMatcherTest {

	private final RegionAliasMatcher matcher = new RegionAliasMatcher();

	@Test
	@DisplayName("성수기는 성동구로 match하지 않고 성수동은 성동구로 match한다")
	void avoidsSeasonFalsePositive() {
		assertThat(matcher.match("아파트 성수기 거래 증가")).doesNotContain(NewsRegionBucket.SEOUL_SEONGDONG_GU);
		assertThat(matcher.match("성수동 아파트 거래 증가")).contains(NewsRegionBucket.SEOUL_SEONGDONG_GU);
	}
}
