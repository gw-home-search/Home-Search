package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.buildingmetadata.BuildingMetadataProviderException;
import com.home.application.ingest.buildingmetadata.BuildingMetadataSourceResponse;
import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingMetadataJsonParserTest {
    private static final String PNU = "1168010300101400001";
    private final BuildingMetadataJsonParser parser = new BuildingMetadataJsonParser(new ObjectMapper());

    @Test
    @DisplayName("건축물대장 parser는 공동주택 02000만 남기고 관리번호와 면적을 파싱한다")
    void parsesOnlyApartmentBuildingCandidates() {
        var parsed = parser.parse(snapshot(BuildingMetadataSourceKind.BLD_RECAP_TITLE, """
			{"response":{"body":{"totalCount":2,"items":{"item":[
			{"mainPurpsCd":"01000","mgmBldrgstPk":"ignored","bldNm":"상가"},
			{"mainPurpsCd":"02000","mgmBldrgstPk":"bld-1","bldNm":"대장 이름","hhldCnt":740,
			 "mainBldCnt":8,"platArea":12345.67,"archArea":2345.67,"totArea":98765.43,
			 "bcRat":22.5,"vlRat":199.8,"useAprDay":"2015-03-20"}]}}}}
			"""));

        assertThat(parsed.totalCount()).isEqualTo(2);
        assertThat(parsed.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.sourceKey()).isEqualTo("bld-1");
            assertThat(candidate.values().platArea()).isEqualByComparingTo("12345.67");
            assertThat(candidate.values().dongCnt()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("HTTP 200 provider 오류는 정상 빈 결과가 아니라 provider failure다")
    void rejectsProviderErrorEnvelope() {
        assertThatThrownBy(() -> parser.parse(snapshot(BuildingMetadataSourceKind.BLD_TITLE, """
			{"response":{"header":{"resultCode":"99","resultMsg":"SERVICE ERROR"},"body":{"totalCount":0}}}
			""")))
                .isInstanceOf(BuildingMetadataProviderException.class)
                .hasMessageContaining("resultCode=99");
    }

    private BuildingMetadataSourceResponse snapshot(BuildingMetadataSourceKind kind, String body) {
        return new BuildingMetadataSourceResponse(kind, PNU, 200, "00", body);
    }
}
