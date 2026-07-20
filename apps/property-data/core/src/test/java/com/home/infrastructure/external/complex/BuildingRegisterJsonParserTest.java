package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.buildingregister.BuildingRegisterPageResponse;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BuildingRegisterJsonParserTest {
    private static final String PNU = "1168010300101400001";

    @Test
    @DisplayName("건축물대장 응답 파싱을 검증한다")
    void parsesHierarchyAndRatioCalculationComponentsWithoutFilteringPurpose() {
        String body = """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"pageNo":1,"numOfRows":100,"totalCount":1,"items":{"item":[{
                   "mgmBldrgstPk":"TITLE-1","mgmUpBldrgstPk":"ROOT-1",
                   "regstrGbCd":"1","regstrKindCd":"3","newOldRegstrGbCd":"0","mainAtchGbCd":"1",
                   "bldNm":"관리동","dongNm":"부속","mainPurpsCd":"04000",
                   "platArea":"1000.123456","archArea":"200.123456","totArea":"999.123456",
                   "vlRatEstmTotArea":"800.123456","bcRat":"20.12345678","vlRat":"80.12345678",
                   "mainBldCnt":"2","atchBldCnt":"1","hhldCnt":"740",
                   "useAprDay":"20150320","crtnDay":"20260720"
                 }]}}}}
                """;
        BuildingRegisterJsonParser parser =
                new BuildingRegisterJsonParser(JsonMapper.builder().build());

        var parsed = parser.parse(response(BuildingRegisterEndpoint.TITLE, body));

        assertThat(parsed.providerSuccessful()).isTrue();
        assertThat(parsed.totalCount()).isOne();
        assertThat(parsed.records()).singleElement().satisfies(record -> {
            assertThat(record.managementKey()).isEqualTo("TITLE-1");
            assertThat(record.parentManagementKey()).isEqualTo("ROOT-1");
            assertThat(record.mainPurposeCode()).isEqualTo("04000");
            assertThat(record.floorRatioEstimateTotalArea()).isEqualByComparingTo("800.123456");
            assertThat(record.totalArea()).isEqualByComparingTo("999.123456");
            assertThat(record.creationDate()).isEqualTo("2026-07-20");
        });
    }

    @Test
    @DisplayName("건축물대장 응답 파싱을 검증한다")
    void preservesProviderFailureAsParsedFailureInsteadOfEmpty() {
        String body = """
                {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY ERROR"},
                 "body":{"totalCount":0,"items":""}}}
                """;
        BuildingRegisterJsonParser parser =
                new BuildingRegisterJsonParser(JsonMapper.builder().build());

        var parsed = parser.parse(response(BuildingRegisterEndpoint.RECAP_TITLE, body));

        assertThat(parsed.providerSuccessful()).isFalse();
        assertThat(parsed.records()).isEmpty();
    }

    @Test
    @DisplayName("성공 응답의 pagination 메타데이터가 요청과 다르면 거부한다")
    void rejectsSuccessfulResponseWithMismatchedPaginationMetadata() {
        String body = """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"pageNo":1,"numOfRows":50,"totalCount":100,"items":""}}}
                """;
        BuildingRegisterJsonParser parser =
                new BuildingRegisterJsonParser(JsonMapper.builder().build());

        assertThatThrownBy(() -> parser.parse(response(BuildingRegisterEndpoint.BASIC_OVERVIEW, body)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pagination");
        String wrongPageNo = body.replace("\"pageNo\":1,\"numOfRows\":50", "\"pageNo\":2,\"numOfRows\":100");
        assertThatThrownBy(() -> parser.parse(response(BuildingRegisterEndpoint.BASIC_OVERVIEW, wrongPageNo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pagination");
        String missingPagination = body.replace("\"pageNo\":1,\"numOfRows\":50,", "");
        assertThatThrownBy(() -> parser.parse(response(BuildingRegisterEndpoint.BASIC_OVERVIEW, missingPagination)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pagination");
    }

    private BuildingRegisterPageResponse response(BuildingRegisterEndpoint endpoint, String body) {
        return new BuildingRegisterPageResponse(
                endpoint, PNU, 1, 100, 200, body, body.getBytes().length, "a".repeat(64), false);
    }
}
