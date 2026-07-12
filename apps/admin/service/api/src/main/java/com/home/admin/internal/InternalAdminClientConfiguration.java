package com.home.admin.internal;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import com.home.security.jwt.RsaPemKeys;
import com.home.security.jwt.Rs256JwtCodec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
public class InternalAdminClientConfiguration {
    private static final long MAXIMUM_PRIVATE_KEY_BYTES = 16 * 1024;

    @Bean
    Rs256JwtCodec internalAdminJwtCodec() {
        return new Rs256JwtCodec(Clock.systemUTC());
    }

    @Bean
    InternalAdminTokenIssuer internalAdminTokenIssuer(
        Rs256JwtCodec codec,
        @Value("${home.admin.internal.private-key-path}") Path privateKeyPath,
        @Value("${home.admin.internal.key-id}") String keyId,
        @Value("${home.admin.internal.issuer}") String issuer,
        @Value("${home.admin.internal.audience}") String audience,
        @Value("${home.admin.internal.token-lifetime}") Duration lifetime
    ) {
        return new InternalAdminTokenIssuer(codec, loadPrivateKey(privateKeyPath), keyId, issuer, audience, lifetime);
    }

    @Bean
    PropertyAdminClient propertyAdminClient(
        RestClient.Builder builder,
        InternalAdminTokenIssuer tokenIssuer,
        @Value("${home.admin.internal.property-data-base-url}") String baseUrl,
        @Value("${home.admin.internal.connect-timeout}") Duration connectTimeout,
        @Value("${home.admin.internal.read-timeout}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(positive(connectTimeout, "connect timeout"));
        requestFactory.setReadTimeout(positive(readTimeout, "read timeout"));
        RestClient restClient = builder.clone().baseUrl(validatedBaseUri(baseUrl).toString())
            .requestFactory(requestFactory).build();
        return new RestClientPropertyAdminClient(restClient, tokenIssuer);
    }

    URI validatedBaseUri(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
                throw new IllegalArgumentException("invalid property-data base URL");
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid property-data base URL", exception);
        }
    }

    private java.security.PrivateKey loadPrivateKey(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path) || Files.size(path) > MAXIMUM_PRIVATE_KEY_BYTES) {
                throw new IllegalArgumentException("invalid internal JWT private key file");
            }
            return RsaPemKeys.privateKey(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalArgumentException("could not read internal JWT private key", exception);
        }
    }

    private Duration positive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }
}
