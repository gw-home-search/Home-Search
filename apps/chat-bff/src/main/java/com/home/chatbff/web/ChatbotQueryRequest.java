package com.home.chatbff.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatbotQueryRequest(
        @NotBlank @Size(max = 2000) String question, @Valid ConversationContext conversationContext) {
    public ChatbotQueryRequest {
        question = question == null ? null : question.strip();
    }

    public record ConversationContext(@Size(max = 12) List<@Valid ConversationMessage> messages) {
        public ConversationContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
            if (messages.stream()
                            .mapToInt(message -> message.content().length())
                            .sum()
                    > 12_000) {
                throw new IllegalArgumentException("conversation content exceeds limit");
            }
        }
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
