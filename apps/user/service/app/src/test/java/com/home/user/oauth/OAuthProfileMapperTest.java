package com.home.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OAuthProfileMapperTest {
    @Test
    void mapsOnlyVerifiedGoogleEmail() {
        var mapped = new GoogleOidcProfileMapper()
                .map(Map.of("sub", " g-1 ", "name", "홍길동", "email", "a@example.com", "email_verified", false));
        assertThat(mapped.providerSubject()).isEqualTo("g-1");
        assertThat(mapped.profile().email()).isNull();
    }

    @Test
    void mapsKakaoWhenOptionalConsentBlocksAreMissing() {
        var mapped = new KakaoOAuth2ProfileMapper().map(Map.of("id", 12345L));
        assertThat(mapped.providerSubject()).isEqualTo("12345");
        assertThat(mapped.profile().displayName()).isNull();
    }

    @Test
    void mapsNestedNaverResponseAndRejectsMissingSubject() {
        var mapped = new NaverOAuth2ProfileMapper()
                .map(Map.of("response", Map.of("id", "n-1", "nickname", "네이버 사용자", "email", "null")));
        assertThat(mapped.profile().displayName()).isEqualTo("네이버 사용자");
        assertThat(mapped.profile().email()).isNull();
        assertThatThrownBy(() -> new NaverOAuth2ProfileMapper().map(Map.of("response", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
