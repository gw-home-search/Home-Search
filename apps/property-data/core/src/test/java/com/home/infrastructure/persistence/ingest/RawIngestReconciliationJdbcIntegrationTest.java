package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.reconciliation.RawIngestReconciliationResult;
import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.application.ingest.trade.TradeIngestFinalizer;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMasterBootstrapper;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMatcher;
import com.home.infrastructure.persistence.ingest.matching.JdbcTradeMatchEvidenceRepository;
import com.home.infrastructure.persistence.ingest.normalization.JdbcNormalizedTradeRepository;
import com.home.infrastructure.persistence.ingest.raw.JdbcRawTradeIngestRepository;
import com.home.infrastructure.persistence.ingest.raw.RtmsRawTradeItemParser;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

class RawIngestReconciliationJdbcIntegrationTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("raw reconciliation은 active trade 연결 여부와 무관하게 recoverable RECEIVED를 동일 finalizer로 처리한다")
    void reprocessesEveryRecoverableReceivedRowWithoutActiveTradeRestriction() {
        seedComplex();
        seedRaw(91001, "recoverable-unlinked", "RECEIVED", validPayload());
        seedRaw(91002, "parse-failure-unlinked", "RECEIVED", "{}");
        seedRaw(91003, "already-normalized", "NORMALIZED", validPayload());
        JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
        TradeIngestFinalizer finalizer = transactionalProxy(new TradeIngestFinalizer(
                rawRepository,
                new JdbcNormalizedTradeRepository(jdbcClient, transactionTemplate),
                new JdbcComplexMatcher(jdbcClient),
                new JdbcComplexMasterBootstrapper(jdbcClient, pnu -> Optional.empty()),
                new JdbcTradeMatchEvidenceRepository(jdbcClient)));
        RawIngestReconciliationService service = new RawIngestReconciliationService(
                rawRepository, new RtmsRawTradeItemParser(new ObjectMapper()), finalizer);

        RawIngestReconciliationResult result = service.reconcileReceived(10);

        assertThat(result).isEqualTo(new RawIngestReconciliationResult(2, 1));
        assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED))
                .extracting(raw -> raw.id())
                .containsExactly(91001L, 91003L);
        assertThat(rawRepository.findByStatus(RawTradeIngestStatus.PARSE_FAILED))
                .extracting(raw -> raw.id())
                .containsExactly(91002L);
        assertThat(tradeCount()).isEqualTo(1);
    }

    private void seedRaw(long id, String sourceKey, String status, String payload) {
        jdbcClient
                .sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no,
			    payload, payload_hash, status
			)
			VALUES (:id, 'RTMS', :sourceKey, '11680', '202512', 1, :payload, :payloadHash, :status)
			""")
                .param("id", id)
                .param("sourceKey", sourceKey)
                .param("payload", payload)
                .param("payloadHash", "hash-" + sourceKey)
                .param("status", status)
                .update();
    }

    private String validPayload() {
        return """
			{"aptDong":"101","aptNm":"Sample Apartment","aptSeq":"APT-501","dealAmount":"125,000",\
			"dealDay":1,"dealMonth":12,"dealYear":2025,"excluUseAr":84.93,"floor":12,\
			"jibun":"140-1","sggCd":"11680","umdCd":"10300"}
			""";
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                (TransactionManager) transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return (T) proxyFactory.getProxy();
    }
}
