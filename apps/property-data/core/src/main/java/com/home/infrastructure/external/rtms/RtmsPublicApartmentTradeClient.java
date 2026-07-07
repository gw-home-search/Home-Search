package com.home.infrastructure.external.rtms;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.infrastructure.external.ExternalApiUri;

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
		return fetchPage(request).batch();
	}

	@Override
	public RtmsApartmentTradePage fetchPage(RtmsApartmentTradeRequest request) {
		String query = "_type=json"
			+ "&serviceKey=" + ExternalApiUri.serviceKeyQueryValue(properties.requiredServiceKey())
			+ "&LAWD_CD=" + ExternalApiUri.queryValue(request.lawdCd())
			+ "&DEAL_YMD=" + ExternalApiUri.queryValue(request.dealYmd())
			+ "&pageNo=" + ExternalApiUri.queryValue(request.pageNo())
			+ "&numOfRows=" + ExternalApiUri.queryValue(properties.numOfRows());
		String payload = restClient.get()
			.uri(ExternalApiUri.create(properties.baseUrl(), properties.path(), query))
			.retrieve()
			.body(String.class);
		return parser.parsePage(request.lawdCd(), request.dealYmd(), request.pageNo(), payload);
	}
}
