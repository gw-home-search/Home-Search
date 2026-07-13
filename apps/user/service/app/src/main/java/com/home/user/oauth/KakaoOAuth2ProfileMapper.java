package com.home.user.oauth;
import com.home.domain.user.UserProfile;
import java.util.Map;
public final class KakaoOAuth2ProfileMapper {
    public OAuthProfile map(Map<String,Object> attributes) {
        Map<String,Object> account=OAuthProfileValues.object(attributes.get("kakao_account"));
        Map<String,Object> profile=OAuthProfileValues.object(account.get("profile"));
        String email=Boolean.TRUE.equals(account.get("is_email_valid"))&&Boolean.TRUE.equals(account.get("is_email_verified"))
                ? OAuthProfileValues.text(account.get("email")) : null;
        String image=OAuthProfileValues.text(profile.get("profile_image_url"));
        if(image==null) image=OAuthProfileValues.text(profile.get("thumbnail_image_url"));
        return new OAuthProfile(OAuthProfileValues.requiredSubject(attributes.get("id")),
                new UserProfile(OAuthProfileValues.text(profile.get("nickname")),email,image));
    }
}
