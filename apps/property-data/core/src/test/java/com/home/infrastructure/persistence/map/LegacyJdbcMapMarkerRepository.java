package com.home.infrastructure.persistence.map;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerRepository;
import com.home.application.map.ComplexMarkerResult;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

final class LegacyJdbcMapMarkerRepository implements ComplexMarkerRepository {

    private final JdbcClient jdbcClient;
    private final ComplexMarkerRowMapper rowMapper = new ComplexMarkerRowMapper();
    private final String markerShapeFilterSql;
    private final String tradeFirstSql;

    LegacyJdbcMapMarkerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
        this.markerShapeFilterSql = ComplexMarkerSql.markerShapeFilter();
        this.tradeFirstSql = ComplexMarkerSql.tradeFirst();
    }

    @Override
    public List<ComplexMarkerResult> findComplexMarkers(ComplexMarkerQuery query) {
        ComplexMarkerJdbcParameters parameters = ComplexMarkerJdbcParameters.from(query);
        if (hasMarkerShapeFilter(query)) {
            return parameters
                    .bindMarkerShapeFilter(jdbcClient.sql(markerShapeFilterSql))
                    .query(rowMapper::map)
                    .list();
        }
        return parameters
                .bindTradeFirst(jdbcClient.sql(tradeFirstSql))
                .query(rowMapper::map)
                .list();
    }

    private boolean hasMarkerShapeFilter(ComplexMarkerQuery query) {
        return query.unitMin() != null
                || query.unitMax() != null
                || query.ageMin() != null
                || query.ageMax() != null
                || query.bcRatMin() != null
                || query.bcRatMax() != null
                || query.vlRatMin() != null
                || query.vlRatMax() != null;
    }
}
