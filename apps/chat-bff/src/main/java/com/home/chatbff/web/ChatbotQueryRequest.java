package com.home.chatbff.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatbotQueryRequest(
        @NotBlank @Size(max = 2000) String question,
        @Valid UiContext uiContext,
        @Valid ConversationContext conversationContext) {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public ChatbotQueryRequest {
        question = question == null ? null : question.strip();
    }

    public ChatbotQueryRequest(String question, ConversationContext conversationContext) {
        this(question, null, conversationContext);
    }

    public record UiContext(
            @Valid MapViewport mapViewport, @Valid SelectedComplex selectedComplex) {
        public UiContext {
            if (mapViewport == null && selectedComplex == null) {
                throw new IllegalArgumentException("ui context requires at least one hint");
            }
        }
    }

    public record MapViewport(
            @NotNull @Valid MapBounds bounds,
            @NotNull @Min(1) @Max(12) Integer level) {}

    public record MapBounds(double swLat, double swLng, double neLat, double neLng) {
        public MapBounds {
            if (!Double.isFinite(swLat)
                    || !Double.isFinite(swLng)
                    || !Double.isFinite(neLat)
                    || !Double.isFinite(neLng)
                    || swLat < 33
                    || neLat > 39
                    || swLng < 124
                    || neLng > 132
                    || swLat >= neLat
                    || swLng >= neLng
                    || neLat - swLat > 6
                    || neLng - swLng > 8) {
                throw new IllegalArgumentException("map bounds are outside supported range");
            }
        }
    }

    public record SelectedComplex(
            @NotNull @Positive @Max(MAX_SAFE_INTEGER) Long complexId,
            @NotNull @Positive @Max(MAX_SAFE_INTEGER) Long parcelId) {}

    public record ConversationContext(
            @Size(max = 12) List<@Valid ConversationMessage> messages,
            @Valid ConversationMemory memory) {
        public ConversationContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
            if (messages.stream()
                            .mapToInt(message -> message.content().length())
                            .sum()
                    > 12_000) {
                throw new IllegalArgumentException("conversation content exceeds limit");
            }
        }

        public ConversationContext(List<ConversationMessage> messages) {
            this(messages, null);
        }
    }

    public record ConversationMemory(
            @NotNull @Min(1) @Max(2) Integer version,
            @Positive @Max(MAX_SAFE_INTEGER) Long complexId,
            @Size(min = 2, max = 5) List<@NotNull @Positive @Max(MAX_SAFE_INTEGER) Long> complexIds,
            @Pattern(regexp = "[0-9]{2,10}") String regionCode,
            @NotNull ScopeKind scopeKind) {
        public ConversationMemory {
            complexIds = complexIds == null ? null : List.copyOf(complexIds);
            if (version != null && version == 2) {
                if (scopeKind != ScopeKind.RECOMMENDATION
                        || complexId != null
                        || complexIds == null
                        || complexIds.size() < 2
                        || complexIds.stream().distinct().count() != complexIds.size()) {
                    throw new IllegalArgumentException("recommendation memory is invalid");
                }
            } else if (scopeKind == ScopeKind.RECOMMENDATION || complexIds != null) {
                throw new IllegalArgumentException("version 1 memory cannot contain recommendation candidates");
            }
            if (scopeKind == ScopeKind.COMPLEX && complexId == null) {
                throw new IllegalArgumentException("COMPLEX memory requires complexId");
            }
            if (scopeKind == ScopeKind.ADMIN_REGION && regionCode == null) {
                throw new IllegalArgumentException("ADMIN_REGION memory requires regionCode");
            }
        }

        public ConversationMemory(Integer version, Long complexId, String regionCode, ScopeKind scopeKind) {
            this(version, complexId, null, regionCode, scopeKind);
        }
    }

    public enum ScopeKind {
        COMPLEX,
        ADMIN_REGION,
        MAP_VIEWPORT,
        RECOMMENDATION
    }

    public record ConversationMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 2000) String content) {
        public ConversationMessage {
            role = role == null ? null : role.strip();
            content = content == null ? null : content.strip();
        }
    }
}
