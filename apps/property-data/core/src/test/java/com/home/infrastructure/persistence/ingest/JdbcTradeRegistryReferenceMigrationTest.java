package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcTradeRegistryReferenceMigrationTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("latest migration은 registry pair check와 composite trade FK를 VALIDATED로 고정한다")
    void latestMigrationValidatesPairCheckAndCompositeForeignKey() {
        assertThat(validatedConstraint("ck_trade_source_key_registry_trade_pair"))
                .isTrue();
        assertThat(validatedConstraint("fk_trade_source_key_registry_trade")).isTrue();
    }

    @Test
    @DisplayName("registry는 실제 trade의 id/deal_date pair만 참조할 수 있다")
    void registryRejectsMismatchedTradePair() {
        seedComplex();
        seedRawAndTrade();

        assertThatThrownBy(() -> jdbcClient.sql("""
			INSERT INTO trade_source_key_registry (
			    source, source_key, raw_ingest_id, trade_id, trade_deal_date
			)
			VALUES ('RTMS', 'bad-pair', 91, 99, DATE '2025-12-16')
			""").update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("cancellation-only registry는 NULL/NULL pair를 유지할 수 있다")
    void cancellationOnlyRegistryKeepsNullPair() {
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status
			)
			VALUES (92, 'RTMS', 'cancel-only', '11680', '202512', 1, '{}', 'cancel-only-hash', 'RECEIVED')
			""").update();

        assertThatCode(() -> jdbcClient.sql("""
			INSERT INTO trade_source_key_registry (
			    source, source_key, raw_ingest_id, trade_id, trade_deal_date
			)
			VALUES ('RTMS', 'cancel-only', 92, NULL, NULL)
			""").update()).doesNotThrowAnyException();
    }

    private boolean validatedConstraint(String name) {
        return Boolean.TRUE.equals(
                jdbcClient.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = 'public.trade_source_key_registry'::regclass
			  AND conname = :name
			""").param("name", name).query(Boolean.class).single());
    }

    private void seedRawAndTrade() {
        jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status
			)
			VALUES (91, 'RTMS', 'trade-99', '11680', '202512', 1, '{}', 'trade-99-hash', 'NORMALIZED')
			""").update();
        jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, source, source_key,
			    complex_pk, apt_seq, raw_ingest_id
			)
			VALUES (99, 501, DATE '2025-12-15', 125000, 'RTMS', 'trade-99',
			        'COMPLEX-PK-501', 'APT-501', 91)
			""").update();
    }
}
