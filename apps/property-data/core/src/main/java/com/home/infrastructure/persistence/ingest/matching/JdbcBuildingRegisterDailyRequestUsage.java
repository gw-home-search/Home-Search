package com.home.infrastructure.persistence.ingest.matching;

import com.home.application.ingest.buildingregister.BuildingRegisterDailyRequestUsage;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBuildingRegisterDailyRequestUsage implements BuildingRegisterDailyRequestUsage {
    private final JdbcClient jdbc;

    public JdbcBuildingRegisterDailyRequestUsage(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public int usedRequests(LocalDate runDate) {
        Objects.requireNonNull(runDate, "runDate");
        return jdbc.sql("""
                    SELECT count(*)
                    FROM building_register_raw_page raw
                    JOIN building_register_endpoint_snapshot snapshot ON snapshot.id=raw.endpoint_snapshot_id
                    WHERE snapshot.run_date=:run_date
                    """).param("run_date", runDate).query(Integer.class).single();
    }
}
