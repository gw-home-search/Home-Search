package com.home.admin.internal;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public final class RestClientPropertyAdminClient implements PropertyAdminClient {
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final RestClient restClient;
    private final InternalAdminTokenIssuer tokenIssuer;

    public RestClientPropertyAdminClient(RestClient restClient, InternalAdminTokenIssuer tokenIssuer) {
        this.restClient = restClient;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public DownstreamResponse exchange(Request request) {
        RestClient.RequestBodySpec spec = restClient.method(request.method())
            .uri(builder -> {
                builder.path(request.path());
                request.query().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> builder.queryParam(entry.getKey(), entry.getValue()));
                return builder.build();
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenIssuer.issue(request.principal(), request.requestId()))
            .header("X-Request-Id", request.requestId());
        if (request.body() != null) spec.contentType(MediaType.APPLICATION_JSON).body(request.body());
        return spec.exchange((httpRequest, response) -> {
            byte[] body = readBounded(response.getBody());
            MediaType contentType = response.getHeaders().getContentType();
            return new DownstreamResponse(response.getStatusCode().value(), contentType, body);
        });
    }

    private byte[] readBounded(java.io.InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
        if (body.length > MAXIMUM_RESPONSE_BYTES) throw new IOException("property admin response exceeded limit");
        return body;
    }
}
