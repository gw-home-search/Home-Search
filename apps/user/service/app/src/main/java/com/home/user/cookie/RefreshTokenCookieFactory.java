package com.home.user.cookie;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
@Component
public class RefreshTokenCookieFactory {
 private final boolean secure; private final Duration ttl;
 public RefreshTokenCookieFactory(@Value("${home.cookie.secure:true}") boolean secure,@Value("${home.auth.refresh-ttl:30d}") Duration ttl,
                                  @Value("${spring.profiles.active:}") String profiles){if(!secure&&profiles.contains("prod"))throw new IllegalStateException("production refresh cookie must be Secure");this.secure=secure;this.ttl=ttl;}
 public ResponseCookie active(String value){return base(value).maxAge(ttl).build();}
 public ResponseCookie expired(){return base("").maxAge(Duration.ZERO).build();}
 private ResponseCookie.ResponseCookieBuilder base(String value){return ResponseCookie.from("refresh_token",value).httpOnly(true).secure(secure).sameSite("Lax").path("/auth");}
}
