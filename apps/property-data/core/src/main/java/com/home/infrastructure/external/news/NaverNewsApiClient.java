package com.home.infrastructure.external.news;

import com.home.application.news.collection.NewsProviderGateway;
import com.home.application.news.collection.NewsProviderItem;
import com.home.application.news.collection.NewsProviderPage;
import com.home.application.news.collection.NewsProviderQuery;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class NaverNewsApiClient implements NewsProviderGateway {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String path;

    public NaverNewsApiClient(RestClient restClient, ObjectMapper objectMapper, String path) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.path = Objects.requireNonNull(path);
    }

    @Override
    public NewsProviderPage search(NewsProviderQuery query) {
        try {
            ProviderResponse response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("query", query.query())
                            .queryParam("display", query.display())
                            .queryParam("start", query.start())
                            .queryParam("sort", "date")
                            .build())
                    .exchange((request, clientResponse) -> new ProviderResponse(
                            clientResponse.getStatusCode(),
                            clientResponse.getHeaders(),
                            readBounded(clientResponse.getBody())));
            ensureSuccess(response);
            return parse(response.body());
        } catch (NaverNewsProviderException exception) {
            throw exception;
        } catch (RestClientException | IOException | IllegalArgumentException exception) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.TRANSIENT, "NAVER news provider call failed", null, exception);
        }
    }

    private void ensureSuccess(ProviderResponse response) {
        int status = response.status().value();
        if (response.status().is2xxSuccessful()) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.AUTHENTICATION, "NAVER news provider authentication failed");
        }
        if (status == 429) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.DAILY_QUOTA,
                    "NAVER news provider daily quota reached",
                    retryAfterSeconds(response.headers()),
                    null);
        }
        if (response.status().is5xxServerError()) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.TRANSIENT,
                    "NAVER news provider temporarily unavailable",
                    retryAfterSeconds(response.headers()),
                    null);
        }
        throw new NaverNewsProviderException(
                NaverNewsFailureKind.INVALID_RESPONSE, "NAVER news provider returned an invalid status");
    }

    private NewsProviderPage parse(byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        int total = nonNegativeInt(root.path("total"), "total");
        int start = boundedInt(root.path("start"), "start", 1, 1000);
        int display = boundedInt(root.path("display"), "display", 0, 100);
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray() || itemsNode.size() > 100) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.INVALID_RESPONSE, "NAVER news items shape is invalid");
        }
        List<NewsProviderItem> items = new ArrayList<>(itemsNode.size());
        for (int index = 0; index < itemsNode.size(); index++) {
            JsonNode item = itemsNode.get(index);
            items.add(new NewsProviderItem(
                    optionalText(item, "title"),
                    optionalText(item, "originallink"),
                    optionalText(item, "link"),
                    optionalText(item, "description"),
                    optionalText(item, "pubDate"),
                    start,
                    index + 1));
        }
        return new NewsProviderPage(total, start, display, List.copyOf(items));
    }

    private byte[] readBounded(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new NaverNewsProviderException(
                        NaverNewsFailureKind.INVALID_RESPONSE, "NAVER news response size exceeded");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private int nonNegativeInt(JsonNode node, String field) {
        return boundedInt(node, field, 0, Integer.MAX_VALUE);
    }

    private int boundedInt(JsonNode node, String field, int min, int max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new NaverNewsProviderException(NaverNewsFailureKind.INVALID_RESPONSE, field + " is not an integer");
        }
        int value = node.intValue();
        if (value < min || value > max) {
            throw new NaverNewsProviderException(
                    NaverNewsFailureKind.INVALID_RESPONSE, field + " is outside the allowed range");
        }
        return value;
    }

    private Integer retryAfterSeconds(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(value);
            return seconds >= 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record ProviderResponse(HttpStatusCode status, HttpHeaders headers, byte[] body) {}
}
