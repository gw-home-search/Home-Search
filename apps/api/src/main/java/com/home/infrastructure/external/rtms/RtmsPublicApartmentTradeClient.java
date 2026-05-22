package com.home.infrastructure.external.rtms;

import com.home.application.ingest.OpenApiTradeIngestBatch;

import org.springframework.web.client.RestClient;

public class RtmsPublicApartmentTradeClient implements RtmsApartmentTradeClient {

	private final RestClient restClient;
	private final RtmsApartmentTradeProperties properties;
	private final RtmsApartmentTradeResponseParser parser;

	public RtmsPublicApartmentTradeClient(
		RestClient restClient,
		RtmsApartmentTradeProperties properties,
		RtmsApartmentTradeResponseParser parser
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.parser = parser;
	}

	@Override
	public OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request) {
		String serviceKey = properties.requiredServiceKey();
		String payload = restClient.get()
			.uri(uriBuilder -> uriBuilder
				.path(properties.path())
				.queryParam("_type", "json")
				.queryParam("serviceKey", serviceKey)
				.queryParam("LAWD_CD", request.lawdCd())
				.queryParam("DEAL_YMD", request.dealYmd())
				.queryParam("pageNo", request.pageNo())
				.queryParam("numOfRows", properties.numOfRows())
				.build())
			.retrieve()
			.body(String.class);
		return parser.parse(request.lawdCd(), request.dealYmd(), request.pageNo(), payload);
	}
}
