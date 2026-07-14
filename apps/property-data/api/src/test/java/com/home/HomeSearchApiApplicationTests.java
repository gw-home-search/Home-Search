package com.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.map.MapUseCase;
import com.home.application.region.RegionRelationSynchronizationGateway;
import com.home.application.region.RegionUnitCntSynchronizationService;
import com.home.infrastructure.persistence.map.JdbcMapMarkerRepository;
import com.home.infrastructure.persistence.map.JdbcRegionMarkerRepository;
import com.home.infrastructure.persistence.propertydetail.JdbcPropertyDetailReader;
import com.home.infrastructure.persistence.regionnavigation.JdbcRegionNavigationReader;
import com.home.infrastructure.persistence.search.JdbcComplexSearchReader;
import com.home.infrastructure.persistence.tradehistory.JdbcTradeHistoryReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "home.ingest.rtms.daily.enabled=true")
@ActiveProfiles("test")
class HomeSearchApiApplicationTests {

    @MockitoBean
    private MapUseCase mapUseCase;

    @MockitoBean
    private JdbcMapMarkerRepository mapMarkerRepository;

    @MockitoBean
    private JdbcRegionMarkerRepository regionMarkerRepository;

    @MockitoBean
    private JdbcComplexSearchReader complexSearchReader;

    @MockitoBean
    private JdbcRegionNavigationReader regionNavigationReader;

    @MockitoBean
    private JdbcPropertyDetailReader propertyDetailReader;

    @MockitoBean
    private JdbcTradeHistoryReader tradeHistoryReader;

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
        assertThat(applicationContext.containsBean("regionUnitCntSyncApplicationRunner"))
                .isFalse();
    }

    @Test
    @DisplayName("legacy RTMS daily property를 활성화해도 API scheduler bean은 없다")
    void legacyRtmsDailySchedulerIsAbsent() {
        assertThat(applicationContext.containsBean("rtmsDailyRefreshScheduler")).isFalse();
    }

    @Test
    @DisplayName("region sync DB 의존성은 no-DB 부트가 아니라 실행 시점에 검증한다")
    void regionSyncRequiresDatabaseOnlyWhenInvoked() {
        assertThatThrownBy(regionRelationSynchronizationGateway::synchronizeAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PlatformTransactionManager is required for region unit count persistence");
    }

    @Test
    @DisplayName("runtime DML 복구 runner만 기본 등록되고 partition DDL runner는 opt-in이다")
    void defaultOnRecoveryRunnersAreRegisteredWithoutDatabase() {
        assertThat(applicationContext.containsBean("rawIngestReconciliationRunner"))
                .isTrue();
        assertThat(applicationContext.containsBean("tradePartitionMaintenanceRunner"))
                .isFalse();
        assertThat(applicationContext.containsBean("rtmsOneShotIngestApplicationRunner"))
                .isFalse();
    }

    @Test
    @DisplayName("complex relation use case는 no-DB 부트에서도 정의되고 사용 시점에만 DB를 요구한다")
    void complexRelationBeansAreDefinedWithoutDatabase() {
        assertThat(applicationContext.containsBean("complexRelationUseCase")).isTrue();
    }
}
