package com.home.user.oauth;
import com.home.domain.user.UserProfile;
import java.util.Map;
public final class NaverOAuth2ProfileMapper {
    public OAuthProfile map(Map<String,Object> attributes) {
        Map<String,Object> response=OAuthProfileValues.object(attributes.get("response"));
        String name=OAuthProfileValues.text(response.get("nickname")); if(name==null) name=OAuthProfileValues.text(response.get("name"));
        return new OAuthProfile(OAuthProfileValues.requiredSubject(response.get("id")), new UserProfile(
                name,OAuthProfileValues.text(response.get("email")),OAuthProfileValues.text(response.get("profile_image"))));
    }
}
