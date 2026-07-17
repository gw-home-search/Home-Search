package com.home.chatbff.auth;

public record VerifiedChatUser(long userId) {
    public VerifiedChatUser {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
