package com.home.admin.internal;

import java.util.Map;

import com.home.admin.security.AdminPrincipal;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public interface PropertyAdminClient {
    DownstreamResponse exchange(Request request);

    record Request(HttpMethod method, String path, Map<String, String> query, Object body,
                   AdminPrincipal principal, String requestId) {
        public Request {
            query = query == null ? Map.of() : Map.copyOf(query);
            if (method == null || path == null || !path.startsWith("/internal/v1/admin/")
                || principal == null || requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("invalid property admin request");
            }
        }
    }

    record DownstreamResponse(int status, MediaType contentType, byte[] body) {
        public DownstreamResponse {
            if (status < 100 || status > 599) throw new IllegalArgumentException("invalid downstream status");
            contentType = contentType == null ? MediaType.APPLICATION_JSON : contentType;
            body = body == null ? new byte[0] : body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
        public boolean successful() { return status >= 200 && status < 300; }
    }
}
