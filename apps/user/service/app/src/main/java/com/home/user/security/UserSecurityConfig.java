package com.home.user.security;

import com.home.user.config.properties.AuthProperties;
import com.home.user.oauth.NoopOAuth2AuthorizedClientRepository;
import com.home.user.oauth.OAuthLoginFailureHandler;
import com.home.user.oauth.OAuthLoginSuccessHandler;
import com.home.user.oauth.SupportedOAuth2AuthorizationRequestResolver;
import com.home.user.oauth.UserOAuth2UserService;
import com.home.user.oauth.UserOidcUserService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class UserSecurityConfig {
    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            AuthOriginFilter origin,
            UserAuthenticationEntryPoint entryPoint,
            NoopOAuth2AuthorizedClientRepository authorizedClients,
            SupportedOAuth2AuthorizationRequestResolver authorizationRequests,
            UserOAuth2UserService oauth,
            UserOidcUserService oidc,
            OAuthLoginSuccessHandler success,
            OAuthLoginFailureHandler failure)
            throws Exception {
        http.securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .cors(c -> {})
                .csrf(c -> c.ignoringRequestMatchers(
                        "/auth/access",
                        "/auth/logout",
                        "/api/v1/favorites/**",
                        "/api/v1/insights/subscription",
                        "/api/v1/insights/inbox"))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(
                                "/oauth2/authorization/**",
                                "/login/oauth2/code/**",
                                "/auth/access",
                                "/auth/logout",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers("/api/v1/users/me")
                        .authenticated()
                        .requestMatchers("/api/v1/favorites/**")
                        .hasRole("USER")
                        .requestMatchers("/api/v1/insights/subscription", "/api/v1/insights/inbox")
                        .hasRole("USER")
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .oauth2Login(o -> o.authorizedClientRepository(authorizedClients)
                        .authorizationEndpoint(a -> a.authorizationRequestResolver(authorizationRequests))
                        .userInfoEndpoint(u -> u.userService(oauth).oidcUserService(oidc))
                        .successHandler(success)
                        .failureHandler(failure))
                .oauth2ResourceServer(o -> o.authenticationEntryPoint(entryPoint)
                        .jwt(j -> j.jwtAuthenticationConverter(
                                jwt -> new UserJwtAuthenticationToken(Long.parseLong(jwt.getSubject())))))
                .addFilterBefore(origin, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        var c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(properties.allowedOrigin().toString()));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
