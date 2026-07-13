package com.home.user.oauth;
import com.home.domain.user.UserProfile;
import java.util.Map;
public final class GoogleOidcProfileMapper {
    public OAuthProfile map(Map<String,Object> attributes) {
        String email = Boolean.TRUE.equals(attributes.get("email_verified")) ? OAuthProfileValues.text(attributes.get("email")) : null;
        return new OAuthProfile(OAuthProfileValues.requiredSubject(attributes.get("sub")), new UserProfile(
                OAuthProfileValues.text(attributes.get("name")), email, OAuthProfileValues.text(attributes.get("picture"))));
    }
}
