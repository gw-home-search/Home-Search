package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcMigrationTestSupport;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketNewsCorrectionRetentionGrantMigrationTest extends JdbcMigrationTestSupport {

    @Test
    @DisplayName("V29는 runtime에 execution 보정 근거 조회·retention 권한만 추가한다")
    void grantsOnlyRequiredCorrectionRetentionPrivilege() {
        flyway(MigrationVersion.fromVersion("28")).clean();
        flyway(MigrationVersion.fromVersion("28")).migrate();

        assertThat(hasPrivilege("market_news_execution_aggregate_correction", "DELETE"))
                .isFalse();
        assertThat(hasPrivilege("market_news_execution_failure_correction", "DELETE"))
                .isFalse();

        flyway(null).migrate();

        assertThat(hasPrivilege("market_news_execution_aggregate_correction", "DELETE"))
                .isTrue();
        assertThat(hasPrivilege("market_news_execution_failure_correction", "DELETE"))
                .isTrue();
        assertThat(hasPrivilege("market_news_execution_aggregate_correction", "SELECT"))
                .isTrue();
        assertThat(hasPrivilege("market_news_execution_failure_correction", "SELECT"))
                .isTrue();
        assertThat(hasPrivilege("market_news_execution_aggregate_correction", "INSERT"))
                .isFalse();
        assertThat(hasPrivilege("market_news_execution_failure_correction", "UPDATE"))
                .isFalse();
    }

    private boolean hasPrivilege(String table, String privilege) {
        return jdbcClient
                .sql("""
                    SELECT has_table_privilege(
                        'home_search_property_runtime',
                        :table,
                        :privilege
                    )
                    """)
                .param("table", table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single();
    }
}
