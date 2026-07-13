package com.home.user.config;
import com.home.application.auth.RefreshTokenService;
import com.home.application.auth.port.OpaqueTokenGenerator;
import com.home.application.auth.port.RefreshTokenRepository;
import com.home.application.user.CurrentUserQueryService;
import com.home.application.user.OAuthLoginService;
import com.home.application.user.port.IdentityLock;
import com.home.application.user.port.UserRepository;
import com.home.application.favorite.GetFavoriteComplex;
import com.home.application.favorite.ListFavoriteComplexes;
import com.home.application.favorite.RemoveFavoriteComplex;
import com.home.application.favorite.SaveFavoriteComplex;
import com.home.application.favorite.port.FavoriteComplexRepository;
import com.home.domain.user.favorite.FavoriteLimitPolicy;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;
@Configuration
public class UserServiceBeans {
 @Bean OAuthLoginService oauthLoginService(UserRepository r,IdentityLock l){return new OAuthLoginService(r,l);}
 @Bean CurrentUserQueryService currentUserQueryService(UserRepository r){return new CurrentUserQueryService(r);}
 @Bean OpaqueTokenGenerator opaqueTokenGenerator(){SecureRandom random=new SecureRandom();return ()->{byte[] bytes=new byte[48];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);};}
 @Bean RefreshTokenService refreshTokenService(RefreshTokenRepository r,OpaqueTokenGenerator g,@Value("${home.auth.refresh-ttl:30d}") Duration ttl){return new RefreshTokenService(r,g,Instant::now,ttl);}
 @Bean FavoriteLimitPolicy favoriteLimitPolicy(){return new FavoriteLimitPolicy();}
 @Bean SaveFavoriteComplex saveFavoriteComplex(FavoriteComplexRepository r,FavoriteLimitPolicy p){return new SaveFavoriteComplex(r,p,java.time.Clock.systemUTC());}
 @Bean RemoveFavoriteComplex removeFavoriteComplex(FavoriteComplexRepository r){return new RemoveFavoriteComplex(r);}
 @Bean GetFavoriteComplex getFavoriteComplex(FavoriteComplexRepository r){return new GetFavoriteComplex(r);}
 @Bean ListFavoriteComplexes listFavoriteComplexes(FavoriteComplexRepository r){return new ListFavoriteComplexes(r);}
 @Bean DefaultCookieSerializer oauthSessionCookieSerializer(@Value("${home.cookie.secure:true}")boolean secure){var serializer=new DefaultCookieSerializer();serializer.setCookieName("OAUTH_SESSION");serializer.setUseHttpOnlyCookie(true);serializer.setUseSecureCookie(secure);serializer.setSameSite("Lax");serializer.setCookiePath("/");return serializer;}
}
