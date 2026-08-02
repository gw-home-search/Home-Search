package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMapMarkerSourceWatermarkReaderTest {

    @Test
    @DisplayName("map marker watermark는 운영 테이블을 scan하지 않는다")
    void currentWatermarkDoesNotScanOperationalTables() {
        assertThat(JdbcMapMarkerSourceWatermarkReader.CURRENT_WATERMARK_SQL)
                .contains("pg_current_wal_lsn()")
                .doesNotContain("max(")
                .doesNotContain("FROM trade")
                .doesNotContain("FROM raw_trade_ingest")
                .doesNotContain("FROM complex");
    }
}
