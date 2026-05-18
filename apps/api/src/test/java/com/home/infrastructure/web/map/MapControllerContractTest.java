package com.home.infrastructure.web.map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.home.application.map.MapUseCase;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

@WebMvcTest(MapController.class)
class MapControllerContractTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MapUseCase mapUseCase;

	@Test
	@DisplayName("POST /api/v1/map/complexes returns canonical V1 complex marker fields")
	void validComplexMarkerRequestReturnsCanonicalMarkerFields() throws Exception {
		given(mapUseCase.getComplexMarkers(any(ComplexMarkersRequest.class)))
			.willReturn(List.of(new ComplexMarkerResponse(1001L, 37.5123, 127.0456, 125000L, 740L)));

		mockMvc.perform(post("/api/v1/map/complexes")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "swLat": 37.45,
					  "swLng": 126.85,
					  "neLat": 37.70,
					  "neLng": 127.20,
					  "pyeongMin": null,
					  "pyeongMax": null,
					  "priceEokMin": null,
					  "priceEokMax": null,
					  "ageMin": null,
					  "ageMax": null,
					  "unitMin": null,
					  "unitMax": null
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$[0].parcelId").value(1001))
			.andExpect(jsonPath("$[0].lat").value(37.5123))
			.andExpect(jsonPath("$[0].lng").value(127.0456))
			.andExpect(jsonPath("$[0].latestDealAmount").value(125000))
			.andExpect(jsonPath("$[0].unitCntSum").value(740))
			.andExpect(jsonPath("$[0].id").doesNotExist())
			.andExpect(jsonPath("$[0].latitude").doesNotExist())
			.andExpect(jsonPath("$[0].longitude").doesNotExist())
			.andExpect(jsonPath("$[0].complexPk").doesNotExist())
			.andExpect(jsonPath("$[0].aptSeq").doesNotExist())
			.andExpect(jsonPath("$[0].source").doesNotExist())
			.andExpect(jsonPath("$[0].sourceKey").doesNotExist());
	}
}
