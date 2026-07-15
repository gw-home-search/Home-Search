package com.home.admin.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class AdminSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    AdminAbsoluteSessionLifetimeFilter adminAbsoluteSessionLifetimeFilter(
            @Value("${home.admin.session.absolute-lifetime:8h}") Duration absoluteLifetime) {
        return new AdminAbsoluteSessionLifetimeFilter(absoluteLifetime, Clock.systemUTC());
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            AdminAbsoluteSessionLifetimeFilter absoluteSessionLifetimeFilter,
            AdminSecurityProblemHandler problemHandler)
            throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookiePath("/");
        return http.csrf(config -> config.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/api/v1/admin/auth/login"))
                .securityContext(context -> context.securityContextRepository(securityContextRepository()))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/admin/auth/login")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(absoluteSessionLifetimeFilter, SecurityContextHolderFilter.class)
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }
}
