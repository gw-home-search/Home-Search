package com.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.home.application.map.MapUseCase;
import com.home.application.region.RegionRelationSynchronizationGateway;
import com.home.application.region.RegionUnitCntSynchronizationService;

@SpringBootTest
@ActiveProfiles("test")
class HomeSearchApiApplicationTests {

	@MockitoBean
	private MapUseCase mapUseCase;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private RegionRelationSynchronizationGateway regionRelationSynchronizationGateway;

	@Autowired
	private RegionUnitCntSynchronizationService regionUnitCntSynchronizationService;

	@Test
	@DisplayName("Spring Boot context는 test profile로 load된다")
	void contextLoads() {
		assertThat(regionRelationSynchronizationGateway).isNotNull();
		assertThat(regionUnitCntSynchronizationService).isNotNull();
		assertThat(applicationContext.containsBean("regionUnitCntSyncApplicationRunner")).isFalse();
	}

	@Test
	@DisplayName("region sync DB 의존성은 no-DB 부트가 아니라 실행 시점에 검증한다")
	void regionSyncRequiresDatabaseOnlyWhenInvoked() {
		assertThatThrownBy(regionRelationSynchronizationGateway::synchronizeAll)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("PlatformTransactionManager is required for region unit count persistence");
	}

	@Test
	@DisplayName("기본 ON 복구 runner들은 no-DB 부트에서도 등록되고 실행 시점에만 DB를 요구한다")
	void defaultOnRecoveryRunnersAreRegisteredWithoutDatabase() {
		assertThat(applicationContext.containsBean("rawIngestReconciliationRunner")).isTrue();
		assertThat(applicationContext.containsBean("tradePartitionMaintenanceRunner")).isTrue();
	}

	@Test
	@DisplayName("complex relation Bean들은 no-DB 부트에서도 정의되고 사용 시점에만 DB를 요구한다")
	void complexRelationBeansAreDefinedWithoutDatabase() {
		assertThat(applicationContext.containsBean("complexRelationUseCase")).isTrue();
		assertThat(applicationContext.containsBean("complexRelationCaseRepository")).isTrue();
		assertThat(applicationContext.containsBean("complexRelationCaseService")).isTrue();
	}
}
