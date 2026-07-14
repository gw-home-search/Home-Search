package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.application.read.TradeTrendPoint;
import com.home.application.tradehistory.TradeHistoryReader;
import com.home.application.tradehistory.TradeHistoryService;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TradeHistorySnapshotConsistencyTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("trade parent, count, content는 concurrent commit 중에도 같은 repeatable-read snapshot을 사용한다")
    void tradeListUsesOneRepeatableReadSnapshot() throws Exception {
        seedPropertyExplorationData();
        InterleavingTradeHistoryReader reader = new InterleavingTradeHistoryReader(jdbcClient);
        TradeHistoryService service = transactionalProxy(new TradeHistoryService(reader));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<TradeListResult> read = executor.submit(() -> service.getTradeList(1001L, null, 0, 25));

            reader.awaitParentRead();
            insertTrade(9003L, "snapshot-9003");
            reader.releaseCountRead();
            reader.awaitCountRead();
            insertTrade(9004L, "snapshot-9004");
            reader.releaseContentRead();

            TradeListResult result = read.get(10, TimeUnit.SECONDS);
            assertThat(result.totalElements()).isEqualTo(result.trades().size());
            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(reader.transactionActive()).isTrue();
            assertThat(reader.transactionIsolation()).isEqualTo("repeatable read");
        } finally {
            executor.shutdownNow();
        }
    }

    private TradeHistoryService transactionalProxy(TradeHistoryService target) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                (TransactionManager) transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return (TradeHistoryService) proxyFactory.getProxy();
    }

    private void insertTrade(long id, String sourceKey) {
        jdbcClient
                .sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no,
			    payload, payload_hash, status, processed_at
			)
			VALUES (:id, 'RTMS', :sourceKey, '11680', '202512', 1,
			        '{}', :payloadHash, 'NORMALIZED', now())
			""")
                .param("id", id)
                .param("sourceKey", sourceKey)
                .param("payloadHash", "hash-" + sourceKey)
                .update();
        jdbcClient
                .sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area,
			    apt_dong, source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES (:id, 501, DATE '2025-12-20', :dealAmount, 18, 84.93,
			        '101', 'RTMS', :sourceKey, 'COMPLEX-PK-501', 'APT-501', :id)
			""")
                .param("id", id)
                .param("dealAmount", 140000L + id)
                .param("sourceKey", sourceKey)
                .update();
    }

    private static class InterleavingTradeHistoryReader implements TradeHistoryReader {

        private final JdbcClient jdbcClient;
        private final CountDownLatch parentRead = new CountDownLatch(1);
        private final CountDownLatch allowCountRead = new CountDownLatch(1);
        private final CountDownLatch countRead = new CountDownLatch(1);
        private final CountDownLatch allowContentRead = new CountDownLatch(1);
        private boolean transactionActive;
        private String transactionIsolation;

        private InterleavingTradeHistoryReader(JdbcClient jdbcClient) {
            this.jdbcClient = jdbcClient;
        }

        @Override
        public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            transactionIsolation = jdbcClient
                    .sql("SHOW transaction_isolation")
                    .query(String.class)
                    .single();
            boolean parentExists = Boolean.TRUE.equals(jdbcClient
                    .sql("""
				SELECT EXISTS (
				    SELECT 1
				    FROM parcel p
				    JOIN complex c ON c.parcel_id = p.id
				    WHERE p.id = :parcelId
				)
				""")
                    .param("parcelId", parcelId)
                    .query(Boolean.class)
                    .single());
            parentRead.countDown();
            await(allowCountRead);
            if (!parentExists) {
                return Optional.empty();
            }

            long totalElements = jdbcClient
                    .sql("""
				SELECT count(*)
				FROM trade t
				JOIN complex c ON c.id = t.complex_id
				WHERE c.parcel_id = :parcelId
				  AND t.deleted_at IS NULL
				""")
                    .param("parcelId", parcelId)
                    .query(Long.class)
                    .single();
            countRead.countDown();
            await(allowContentRead);

            List<TradeResult> trades = jdbcClient.sql("""
				SELECT t.id
				FROM trade t
				JOIN complex c ON c.id = t.complex_id
				WHERE c.parcel_id = :parcelId
				  AND t.deleted_at IS NULL
				ORDER BY t.id
				""").param("parcelId", parcelId).query(Long.class).list().stream()
                    .map(id -> new TradeResult(id, null, null, 0L, null, null))
                    .toList();
            return Optional.of(new TradeListResult(parcelId, complexId, trades, page, size, totalElements));
        }

        @Override
        public Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size) {
            return Optional.empty();
        }

        @Override
        public Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId) {
            return Optional.empty();
        }

        @Override
        public Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId) {
            return Optional.empty();
        }

        private void awaitParentRead() throws InterruptedException {
            assertThat(parentRead.await(10, TimeUnit.SECONDS)).isTrue();
        }

        private void releaseCountRead() {
            allowCountRead.countDown();
        }

        private void awaitCountRead() throws InterruptedException {
            assertThat(countRead.await(10, TimeUnit.SECONDS)).isTrue();
        }

        private void releaseContentRead() {
            allowContentRead.countDown();
        }

        private boolean transactionActive() {
            return transactionActive;
        }

        private String transactionIsolation() {
            return transactionIsolation;
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for snapshot interleaving");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("snapshot interleaving interrupted", exception);
            }
        }
    }
}
