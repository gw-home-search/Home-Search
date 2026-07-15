package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMarkerSqlTest {

    @Test
    @DisplayName("map SQL catalog는 완성형 resource를 한 번 로드해 재사용한다")
    void loadsCompleteSqlResourcesOnce() {
        String markerShapeFilter = ComplexMarkerSql.markerShapeFilter();
        String tradeFirst = ComplexMarkerSql.tradeFirst();

        assertThat(ComplexMarkerSql.markerShapeFilter()).isSameAs(markerShapeFilter);
        assertThat(ComplexMarkerSql.tradeFirst()).isSameAs(tradeFirst);
        assertThat(markerShapeFilter)
                .startsWith("WITH requested_bounds")
                .contains("filtered_markers AS", ":unitMin", ":ageMin")
                .doesNotContain("%s");
        assertThat(tradeFirst)
                .startsWith("WITH requested_bounds")
                .contains("latest_parcel_trade AS", ":priceMin", ":areaMin")
                .doesNotContain("%s");
    }
}
