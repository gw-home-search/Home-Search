package com.home.user.web;

import com.home.application.user.OAuthLoginResult;

public record MeResponse(long userId, String provider, String displayName, String email, String profileImage) {
    public static MeResponse from(OAuthLoginResult user) {
        return new MeResponse(
                user.userId(),
                user.provider().name(),
                user.profile().displayName(),
                user.profile().email(),
                user.profile().profileImage());
    }
}
