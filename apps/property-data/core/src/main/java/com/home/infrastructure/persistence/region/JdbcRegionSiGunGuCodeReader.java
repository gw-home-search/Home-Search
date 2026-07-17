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
        return jdbcClient().sql("""
                        SELECT lawd_cd
                        FROM (
                            SELECT code AS lawd_cd
                            FROM region
                            WHERE region_type = 'si-gun-gu'
                            UNION
                            SELECT substring(child.code FROM 1 FOR 5) AS lawd_cd
                            FROM region child
                            JOIN region parent ON parent.id = child.parent_id
                            WHERE child.region_type = 'eup-myeon-dong'
                              AND parent.region_type = 'si-do'
                              AND child.code ~ '^[0-9]{8}$'
                        ) nationwide_lawd_code
                        ORDER BY lawd_cd
                        """).query(String.class).list();
    }

    private JdbcClient jdbcClient() {
        return Objects.requireNonNull(jdbcClientSupplier.get(), "JdbcClient supplier returned null");
    }
}
