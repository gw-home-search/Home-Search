package com.home.global.error;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class ApiProblemFactory {

    private static final URI ERROR_TYPE = URI.create("/docs/index.html#error-code-list");
    private static final DateTimeFormatter ERROR_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private ApiProblemFactory() {}

    public static ProblemDetail api(HttpStatus status, String title, String detail, String exception) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(ERROR_TYPE);
        problemDetail.setTitle(title);
        return withMetadata(problemDetail, exception);
    }

    public static ProblemDetail internalAdminAuthenticationFailure() {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Internal admin authentication failed.");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setTitle("Unauthorized");
        return withMetadata(problemDetail, "InternalAdminAuthenticationException");
    }

    public static Map<String, Object> body(ProblemDetail problemDetail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problemDetail.getType().toString());
        body.put("title", problemDetail.getTitle());
        body.put("status", problemDetail.getStatus());
        body.put("detail", problemDetail.getDetail());
        if (problemDetail.getProperties() != null) {
            body.putAll(problemDetail.getProperties());
        }
        return Map.copyOf(body);
    }

    private static ProblemDetail withMetadata(ProblemDetail problemDetail, String exception) {
        problemDetail.setProperty("exception", exception);
        problemDetail.setProperty(
                "timestamp",
                OffsetDateTime.now(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .format(ERROR_TIMESTAMP_FORMATTER));
        return problemDetail;
    }
}
