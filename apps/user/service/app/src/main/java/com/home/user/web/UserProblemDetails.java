package com.home.user.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

public final class UserProblemDetails {
    private static final URI ERROR_TYPE = URI.create("/docs/index.html#error-code-list");

    private UserProblemDetails() {
    }

    public static ProblemDetail create(
            HttpStatus status,
            String title,
            String detailMessage,
            String code,
            String exception) {
        var detail = ProblemDetail.forStatusAndDetail(status, detailMessage);
        detail.setType(ERROR_TYPE);
        detail.setTitle(title);
        detail.setProperty("code", code);
        detail.setProperty("exception", exception);
        detail.setProperty("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return detail;
    }

    public static void write(
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail,
            String code,
            String exception) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{" +
            "\"type\":\"" + ERROR_TYPE + "\"," +
            "\"title\":\"" + title + "\"," +
            "\"status\":" + status.value() + "," +
            "\"detail\":\"" + detail + "\"," +
            "\"code\":\"" + code + "\"," +
            "\"exception\":\"" + exception + "\"," +
            "\"timestamp\":\"" + OffsetDateTime.now(ZoneOffset.UTC) + "\"}");
    }
}
