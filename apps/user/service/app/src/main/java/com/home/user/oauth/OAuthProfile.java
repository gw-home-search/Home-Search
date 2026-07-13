package com.home.user.oauth;
import com.home.domain.user.UserProfile;
public record OAuthProfile(String providerSubject, UserProfile profile) { }
