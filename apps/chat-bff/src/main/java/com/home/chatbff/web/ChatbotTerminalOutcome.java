package com.home.chatbff.web;

enum ChatbotTerminalOutcome {
    SUCCESS,
    PARTIAL,
    CLARIFICATION,
    UPSTREAM_TIMEOUT,
    UPSTREAM_4XX,
    UPSTREAM_5XX,
    INVALID_JSON,
    CONTRACT_REJECTED,
    MISSING_FINAL,
    CLIENT_ABORT,
    INTERNAL_ERROR
}
