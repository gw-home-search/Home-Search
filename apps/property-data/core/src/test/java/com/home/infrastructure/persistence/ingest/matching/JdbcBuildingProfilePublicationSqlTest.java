package com.home.infrastructure.persistence.ingest.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfilePublicationSqlTest {
    @Test
    @DisplayName("83개 typed field는 scope별 wide publication table mapping을 모두 가진다")
    void mapsEveryTypedFieldIntoItsWidePublicationTable() {
        for (BuildingProfileField field : BuildingProfileField.values()) {
            String sql =
                    switch (field.scope()) {
                        case SITE -> JdbcBuildingProfilePublicationSql.SITE;
                        case BUILDING -> JdbcBuildingProfilePublicationSql.BUILDING;
                        case HIERARCHY -> JdbcBuildingProfilePublicationSql.HIERARCHY;
                    };
            String mapping =
                    switch (field) {
                        case MGM_BLDRGST_PK -> "record.mgm_bldrgst_pk";
                        case MGM_UP_BLDRGST_PK -> "record.mgm_up_bldrgst_pk";
                        default -> "'" + field.name() + "'";
                    };
            assertThat(sql).as(field.name()).contains(mapping);
        }
    }
}
