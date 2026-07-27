package com.home.chatbff.ai;

import tools.jackson.databind.JsonNode;

public record ChatbotAiStreamEvent(String event, JsonNode data) {}
