package com.home.infrastructure.external.vworld;

import com.home.application.coordinate.footprint.BuildingFootprintSource;
import com.home.infrastructure.persistence.ingest.ParcelCoordinateResolver;
import com.home.infrastructure.persistence.ingest.CoordinateSourceFirstParcelCoordinateResolver;
import com.home.infrastructure.persistence.ingest.ParcelCoordinateOverrideRepository;
import com.home.infrastructure.persistence.ingest.ParcelCoordinateSourceRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class VworldExternalApiConfiguration {

	@Bean
	VworldParcelCoordinateProperties vworldParcelCoordinateProperties(
		@Value("${vworld.data.base-url:https://api.vworld.kr}") String baseUrl,
		@Value("${vworld.data.vm-wfs-path:/ned/wfs/getBldgisSpceWFS}") String wfsPath,
		@Value("${vworld.data.vw-service-key:${VW_SERVICE_KEY:}}") String serviceKey,
		@Value("${vworld.data.vm-domain:http://localhost:8080/only-local-test}") String domain,
		@Value("${vworld.data.num-of-rows:100}") int numOfRows,
		@Value("${vworld.data.connect-timeout-millis:5000}") int connectTimeoutMillis,
		@Value("${vworld.data.read-timeout-millis:5000}") int readTimeoutMillis
	) {
		return new VworldParcelCoordinateProperties(
			baseUrl,
			wfsPath,
			serviceKey,
			domain,
			numOfRows,
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Bean
	@Lazy
	ParcelCoordinateResolver vworldParcelCoordinateResolver(VworldParcelCoordinateProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
		requestFactory.setReadTimeout(properties.readTimeoutMillis());
		RestClient restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.build();
		return new VworldParcelCoordinateResolver(restClient, properties);
	}

	@Bean
	@Lazy
	BuildingFootprintSource vworldBuildingFootprintSource(VworldParcelCoordinateProperties properties) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeoutMillis());
		requestFactory.setReadTimeout(properties.readTimeoutMillis());
		RestClient restClient = RestClient.builder()
			.requestFactory(requestFactory)
			.baseUrl(properties.baseUrl())
			.build();
		return new VworldBuildingFootprintSource(restClient, properties);
	}

	@Bean
	@Primary
	@Lazy
	ParcelCoordinateResolver parcelCoordinateResolver(
		ParcelCoordinateSourceRepository coordinateSourceRepository,
		ParcelCoordinateOverrideRepository overrideRepository
	) {
		return new CoordinateSourceFirstParcelCoordinateResolver(coordinateSourceRepository, overrideRepository);
	}
}
