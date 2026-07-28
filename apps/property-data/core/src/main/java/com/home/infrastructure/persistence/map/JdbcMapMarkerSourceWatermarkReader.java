package com.home.infrastructure.persistence.map;

import com.home.application.map.MapMarkerSourceWatermarkReader;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMapMarkerSourceWatermarkReader implements MapMarkerSourceWatermarkReader {

    private final JdbcClient jdbcClient;

    public JdbcMapMarkerSourceWatermarkReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public String currentWatermark() {
        return jdbcClient.sql("""
				SELECT format(
				    'wal=%s;raw=%s;trade=%s;deleted=%s;complex=%s',
				    pg_current_wal_lsn(),
				    COALESCE((SELECT max(id) FROM raw_trade_ingest), 0),
				    COALESCE((SELECT max(id) FROM trade), 0),
				    COALESCE((SELECT max(deleted_at) FROM trade)::text, '-'),
				    COALESCE((SELECT max(updated_at) FROM complex)::text, '-')
				)
				""").query(String.class).single();
    }
}
