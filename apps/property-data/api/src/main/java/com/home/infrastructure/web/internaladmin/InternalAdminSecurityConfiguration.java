package com.home.infrastructure.web.internaladmin;

import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;
import com.home.security.jwt.RsaPemKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
@EnableConfigurationProperties(InternalAdminJwtProperties.class)
public class InternalAdminSecurityConfiguration {
    @Bean
    FilterRegistrationBean<InternalAdminJwtAuthenticationFilter> internalAdminJwtFilter(
            ObjectMapper objectMapper, InternalAdminJwtProperties properties) {
        Map<String, PublicKey> keys = loadPublicKeys(properties.publicKeys());
        var filter = new InternalAdminJwtAuthenticationFilter(
                new Rs256JwtCodec(Clock.systemUTC()),
                new JwtVerificationPolicy(
                        properties.issuer(), properties.audience(), properties.maximumLifetime(), keys::get),
                objectMapper);
        FilterRegistrationBean<InternalAdminJwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static Map<String, PublicKey> loadPublicKeys(String locations) {
        if (locations == null || locations.isBlank())
            throw new IllegalArgumentException("internal admin public keys are required");
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String entry : locations.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || keys.containsKey(parts[0])) {
                throw new IllegalArgumentException("invalid internal admin public key mapping");
            }
            try {
                Path path = Path.of(parts[1]);
                if (Files.size(path) > 16_384)
                    throw new IllegalArgumentException("internal admin public key is too large");
                keys.put(parts[0], RsaPemKeys.publicKey(Files.readString(path)));
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("could not read internal admin public key", exception);
            }
        }
        return Map.copyOf(keys);
    }
}
