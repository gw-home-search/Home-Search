package com.home.infrastructure.external.kakao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProvider;
import com.home.application.place.NearbyPlaceProviderResult;
import com.home.application.place.NearbyPlaceProviderUnavailableException;
import com.home.domain.place.NearbyPlaceCategory;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class KakaoNearbyPlaceProvider implements NearbyPlaceProvider {

	private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
	private static final String PLACE_HOST = "place.map.kakao.com";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public KakaoNearbyPlaceProvider(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient);
		this.objectMapper = Objects.requireNonNull(objectMapper);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public NearbyPlaceProviderResult search(
		NearbyPlacePoint center,
		int radiusMeters,
		NearbyPlaceCategory category
	) {
		try {
			byte[] response = restClient.get()
				.uri(uriBuilder -> uriBuilder
					.path("/v2/local/search/category.json")
					.queryParam("category_group_code", kakaoCategoryCode(category))
					.queryParam("x", Double.toString(center.lng()))
					.queryParam("y", Double.toString(center.lat()))
					.queryParam("radius", radiusMeters)
					.queryParam("sort", "distance")
					.queryParam("page", 1)
					.queryParam("size", 15)
					.build())
				.exchange((request, clientResponse) -> readResponse(clientResponse.getStatusCode(), clientResponse.getBody()));
			return mapResponse(category, response);
		}
		catch (NearbyPlaceProviderUnavailableException exception) {
			throw exception;
		}
		catch (RestClientException | IOException | IllegalArgumentException exception) {
			throw new NearbyPlaceProviderUnavailableException("Kakao 장소 조회에 실패했습니다.", exception);
		}
	}

	private byte[] readResponse(HttpStatusCode status, InputStream inputStream) throws IOException {
		if (!status.is2xxSuccessful()) {
			throw new NearbyPlaceProviderUnavailableException("Kakao 장소 조회가 정상 응답을 반환하지 않았습니다.");
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			total += read;
			if (total > MAX_RESPONSE_BYTES) {
				throw new NearbyPlaceProviderUnavailableException("Kakao 장소 응답 허용 크기를 초과했습니다.");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private NearbyPlaceProviderResult mapResponse(NearbyPlaceCategory category, byte[] response) throws IOException {
		JsonNode root = objectMapper.readTree(response);
		int matchedCount = nonNegativeInt(root.path("meta").path("total_count"), "total_count");
		JsonNode documents = root.path("documents");
		if (!documents.isArray()) {
			throw new IllegalArgumentException("documents is not an array");
		}
		List<NearbyPlaceItem> places = new ArrayList<>(Math.min(documents.size(), 15));
		for (JsonNode document : documents) {
			places.add(mapPlace(document));
		}
		return new NearbyPlaceProviderResult(category, matchedCount, clock.instant(), List.copyOf(places));
	}

	private NearbyPlaceItem mapPlace(JsonNode document) {
		String providerId = requiredText(document, "id");
		String name = requiredText(document, "place_name");
		double lng = finiteDouble(document, "x", -180, 180);
		double lat = finiteDouble(document, "y", -90, 90);
		int distance = nonNegativeInt(document.path("distance"), "distance");
		return new NearbyPlaceItem(
			"kakao:" + providerId,
			name,
			optionalText(document, "category_name"),
			lat,
			lng,
			distance,
			optionalText(document, "address_name"),
			optionalText(document, "road_address_name"),
			optionalText(document, "phone"),
			validatedPlaceUrl(optionalText(document, "place_url"))
		);
	}

	private String kakaoCategoryCode(NearbyPlaceCategory category) {
		return switch (category) {
			case CAFE -> "CE7";
			case RESTAURANT -> "FD6";
			case CONVENIENCE_STORE -> "CS2";
			case HOSPITAL -> "HP8";
			case PHARMACY -> "PM9";
			case SCHOOL -> "SC4";
		};
	}

	private String requiredText(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (value == null) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value;
	}

	private String optionalText(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isTextual() || value.textValue().isBlank()) {
			return null;
		}
		return value.textValue();
	}

	private double finiteDouble(JsonNode node, String field, double min, double max) {
		String text = requiredText(node, field);
		double value = Double.parseDouble(text);
		if (!Double.isFinite(value) || value < min || value > max) {
			throw new IllegalArgumentException(field + " is outside the allowed range");
		}
		return value;
	}

	private int nonNegativeInt(JsonNode node, String field) {
		int value;
		if (node.isIntegralNumber()) {
			if (!node.canConvertToInt()) {
				throw new IllegalArgumentException(field + " is outside the integer range");
			}
			value = node.intValue();
		}
		else if (node.isTextual()) {
			value = Integer.parseInt(node.textValue());
		}
		else {
			throw new IllegalArgumentException(field + " is not an integer");
		}
		if (value < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
		return value;
	}

	private String validatedPlaceUrl(String value) {
		if (value == null) {
			return null;
		}
		URI uri = URI.create(value);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !PLACE_HOST.equalsIgnoreCase(uri.getHost())) {
			throw new IllegalArgumentException("place_url is not allowed");
		}
		return uri.toString();
	}
}
