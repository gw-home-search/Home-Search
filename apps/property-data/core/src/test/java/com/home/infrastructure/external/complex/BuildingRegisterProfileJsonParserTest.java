package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingprofile.BuildingProfileParsedPage;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileValueState;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BuildingRegisterProfileJsonParserTest {
    private final BuildingRegisterProfileJsonParser parser =
            new BuildingRegisterProfileJsonParser(JsonMapper.builder().build());

    @Test
    @DisplayName("문서화 profile 필드를 typed 값으로 보존하고 unknown key를 관찰한다")
    void mapsDocumentedFieldsAndObservesUnknownKeys() {
        String body = """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},"body":{
                  "pageNo":1,"numOfRows":100,"totalCount":1,"items":{"item":[{
                    "mgmBldrgstPk":"ROOT-1","regstrKindCd":"1","platArea":"1000.25",
                    "hhldCnt":"0","engrGrade":"1+","engrRat":"12.3","rserthqkDsgnApplyYn":"1",
                    "unknownFutureField":"future"
                  }]}}}}
                """;

        BuildingProfileParsedPage page =
                parser.parse(BuildingRegisterEndpoint.RECAP_TITLE, "1168010300101400001", 1, 100, body);

        assertThat(page.providerSuccessful()).isTrue();
        assertThat(page.records()).hasSize(1);
        assertThat(page.records()
                        .getFirst()
                        .value(BuildingProfileField.PLAT_AREA)
                        .decimalValue())
                .isEqualByComparingTo("1000.25");
        assertThat(page.records()
                        .getFirst()
                        .value(BuildingProfileField.HHLD_CNT)
                        .state())
                .isEqualTo(BuildingProfileValueState.ZERO);
        assertThat(page.records()
                        .getFirst()
                        .value(BuildingProfileField.ENGR_GRADE)
                        .textValue())
                .isEqualTo("1+");
        assertThat(page.unknownKeys()).containsExactly("unknownFutureField");
    }

    @Test
    @DisplayName("기본개요는 hierarchy field만 lean record로 파싱한다")
    void keepsBasicOverviewLean() {
        String body = """
                {"response":{"header":{"resultCode":"00"},"body":{
                  "pageNo":1,"numOfRows":100,"totalCount":1,"items":{"item":{
                    "mgmBldrgstPk":"TITLE-1","mgmUpBldrgstPk":"ROOT-1","regstrKindCd":"3",
                    "platArea":"999","hhldCnt":"100"
                  }}}}}
                """;

        BuildingProfileParsedPage page =
                parser.parse(BuildingRegisterEndpoint.BASIC_OVERVIEW, "1168010300101400001", 1, 100, body);

        assertThat(page.records().getFirst().values().keySet())
                .containsExactlyInAnyOrder(
                        BuildingProfileField.MGM_BLDRGST_PK,
                        BuildingProfileField.MGM_UP_BLDRGST_PK,
                        BuildingProfileField.REGSTR_GB_CD,
                        BuildingProfileField.REGSTR_KIND_CD,
                        BuildingProfileField.NEW_OLD_REGSTR_GB_CD);
    }
}
