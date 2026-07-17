package com.home.chatbff.auth;

public interface ChatUserAuthenticator {
    VerifiedChatUser authenticate(String authorizationHeader);
}
