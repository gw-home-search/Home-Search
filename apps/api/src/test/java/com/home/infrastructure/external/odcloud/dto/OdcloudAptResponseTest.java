package com.home.infrastructure.external.odcloud.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OdcloudAptResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("ODC apt 응답은 단지명(COMPLEX_NM1/2/3)을 역직렬화한다")
	void deserializesComplexNames() throws Exception {
		OdcloudAptResponse response = objectMapper.readValue("""
			{
			  "data": [
			    {
			      "PNU": "4115010400107270001",
			      "COMPLEX_PK": "APT-501",
			      "COMPLEX_NM1": "한국아파트",
			      "COMPLEX_NM2": "한국",
			      "COMPLEX_NM3": "한국아파트(단지)",
			      "UNIT_CNT": 796
			    }
			  ]
			}
			""", OdcloudAptResponse.class);

		OdcloudAptResponse.Item item = response.getData().get(0);
		assertThat(item.getComplexNm1()).isEqualTo("한국아파트");
		assertThat(item.getComplexNm2()).isEqualTo("한국");
		assertThat(item.getComplexNm3()).isEqualTo("한국아파트(단지)");
	}
}
