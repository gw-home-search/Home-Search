package com.home.infrastructure.persistence.region;

import com.home.application.region.RegionSiGunGuCodeReader;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRegionSiGunGuCodeReader implements RegionSiGunGuCodeReader {

    private final Supplier<JdbcClient> jdbcClientSupplier;

    JdbcRegionSiGunGuCodeReader(Supplier<JdbcClient> jdbcClientSupplier) {
        this.jdbcClientSupplier = Objects.requireNonNull(jdbcClientSupplier);
    }

    @Autowired
    JdbcRegionSiGunGuCodeReader(JdbcClient jdbcClient) {
        this(() -> jdbcClient);
    }

    @Override
    public List<String> siGunGuCodes() {
        return jdbcClient()
                .sql("SELECT code FROM region WHERE region_type = 'si-gun-gu' ORDER BY code")
                .query(String.class)
                .list();
    }

    private JdbcClient jdbcClient() {
        return Objects.requireNonNull(jdbcClientSupplier.get(), "JdbcClient supplier returned null");
    }
}
