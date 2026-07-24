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
                    "regstrGbCdNm":"집합","regstrKindCdNm":"총괄표제부","newOldRegstrGbCdNm":"신대장",
                    "mainAtchGbCdNm":"주건축물","rnum":1,"splotNm":"특수지명","block":"A",
                    "lot":"B","bylotCnt":2,"rserthqkAblty":"VII-0.176g",
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
        assertThat(page.records().getFirst().values().keySet())
                .extracting(Enum::name)
                .contains(
                        "REGSTR_GB_CD_NM",
                        "REGSTR_KIND_CD_NM",
                        "NEW_OLD_REGSTR_GB_CD_NM",
                        "MAIN_ATCH_GB_CD_NM",
                        "RNUM",
                        "SPLOT_NM",
                        "BLOCK",
                        "LOT",
                        "BYLOT_CNT");
        assertThat(BuildingProfileField.fromProviderKey("rserthqkAblty"))
                .contains(BuildingProfileField.RSERTHQK_ABILITY);
        assertThat(page.records().getFirst().value(BuildingProfileField.RNUM).integerValue())
                .isEqualTo(1L);
        assertThat(page.records()
                        .getFirst()
                        .value(BuildingProfileField.BYLOT_CNT)
                        .integerValue())
                .isEqualTo(2L);
        assertThat(page.records()
                        .getFirst()
                        .value(BuildingProfileField.RSERTHQK_ABILITY)
                        .textValue())
                .isEqualTo("VII-0.176g");
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
