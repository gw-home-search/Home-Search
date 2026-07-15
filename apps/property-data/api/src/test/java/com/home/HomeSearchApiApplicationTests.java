package com.home;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"home.ingest.rtms.daily.enabled=true", "home.ingest.raw-reconcile.enabled=false"})
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

    @MockitoBean
    private JdbcClient jdbcClient;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

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
    @DisplayName("no-DB 부트 테스트는 mandatory region persistence 의존성을 mock으로 명시한다")
    void regionSyncUsesExplicitTestDependencies() {
        assertThat(regionRelationSynchronizationGateway).isNotNull();
        assertThat(transactionManager).isNotNull();
    }

    @Test
    @DisplayName("no-DB 부트 테스트는 DB를 사용하는 복구 runner를 명시적으로 비활성화한다")
    void databaseRunnersAreExplicitlyDisabledWithoutDatabase() {
        assertThat(applicationContext.containsBean("rawIngestReconciliationRunner"))
                .isFalse();
        assertThat(applicationContext.containsBean("tradePartitionMaintenanceRunner"))
                .isFalse();
        assertThat(applicationContext.containsBean("rtmsOneShotIngestApplicationRunner"))
                .isFalse();
    }

    @Test
    @DisplayName("complex relation use case는 명시적 JdbcClient test mock으로 구성된다")
    void complexRelationBeansAreDefinedWithoutDatabase() {
        assertThat(applicationContext.containsBean("complexRelationUseCase")).isTrue();
    }
}
