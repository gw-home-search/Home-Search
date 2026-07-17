package com.home.chatbff.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

final class ChatbotProblems {
    private ChatbotProblems() {}

    static ProblemDetail authenticationRequired(String instance, String requestId) {
        return create(
                HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "로그인이 필요합니다.",
                "AUTHENTICATION_REQUIRED",
                instance,
                requestId);
    }

    static ProblemDetail invalidRequest(String instance, String requestId) {
        return create(
                HttpStatus.BAD_REQUEST,
                "Invalid chatbot request",
                "질문 또는 대화 문맥 형식이 올바르지 않습니다.",
                "INVALID_CHATBOT_REQUEST",
                instance,
                requestId);
    }

    static ProblemDetail providerUnavailable(String instance, String requestId) {
        return create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Chatbot unavailable",
                "답변을 생성하지 못했습니다.",
                "CHATBOT_PROVIDER_UNAVAILABLE",
                instance,
                requestId);
    }

    static ProblemDetail rateLimited(String instance, String requestId) {
        return create(
                HttpStatus.TOO_MANY_REQUESTS,
                "Chatbot rate limited",
                "요청 한도를 초과했습니다.",
                "CHATBOT_RATE_LIMITED",
                instance,
                requestId);
    }

    static ProblemDetail rateLimitUnavailable(String instance, String requestId) {
        return create(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Chatbot rate limit unavailable",
                "요청 보호 기능을 확인하지 못했습니다.",
                "CHATBOT_RATE_LIMIT_UNAVAILABLE",
                instance,
                requestId);
    }

    static ProblemDetail timeout(String instance, String requestId) {
        return create(
                HttpStatus.GATEWAY_TIMEOUT,
                "Chatbot timeout",
                "답변 생성 시간이 초과되었습니다.",
                "CHATBOT_TIMEOUT",
                instance,
                requestId);
    }

    private static ProblemDetail create(
            HttpStatus status, String title, String detail, String code, String instance, String requestId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(title);
        problem.setInstance(URI.create(instance));
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId);
        return problem;
    }
}
