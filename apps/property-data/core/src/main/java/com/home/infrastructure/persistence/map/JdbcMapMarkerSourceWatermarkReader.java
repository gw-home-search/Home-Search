package com.home.infrastructure.persistence.map;

import com.home.application.map.MapMarkerSourceWatermarkReader;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMapMarkerSourceWatermarkReader implements MapMarkerSourceWatermarkReader {

    static final String CURRENT_WATERMARK_SQL = """
			SELECT format('wal=%s', pg_current_wal_lsn())
			""";

    private final JdbcClient jdbcClient;

    public JdbcMapMarkerSourceWatermarkReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public String currentWatermark() {
        return jdbcClient.sql(CURRENT_WATERMARK_SQL).query(String.class).single();
    }
}
