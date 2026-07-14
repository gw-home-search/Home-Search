package com.home.admin.internal;

import com.home.admin.config.InternalAdminClientProperties;
import com.home.admin.config.InternalAdminJwtProperties;
import com.home.security.jwt.Rs256JwtCodec;
import com.home.security.jwt.RsaPemKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
@EnableConfigurationProperties({InternalAdminClientProperties.class, InternalAdminJwtProperties.class})
public class InternalAdminClientConfiguration {
    private static final long MAXIMUM_PRIVATE_KEY_BYTES = 16 * 1024;

    @Bean
    Rs256JwtCodec internalAdminJwtCodec() {
        return new Rs256JwtCodec(Clock.systemUTC());
    }

    @Bean
    InternalAdminTokenIssuer internalAdminTokenIssuer(Rs256JwtCodec codec, InternalAdminJwtProperties properties) {
        return new InternalAdminTokenIssuer(
                codec,
                loadPrivateKey(Path.of(properties.privateKeyPath())),
                properties.keyId(),
                properties.issuer(),
                properties.audience(),
                properties.tokenLifetime());
    }

    @Bean
    PropertyAdminClient propertyAdminClient(
            RestClient.Builder builder,
            InternalAdminTokenIssuer tokenIssuer,
            InternalAdminClientProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = builder.clone()
                .baseUrl(properties.propertyDataBaseUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new RestClientPropertyAdminClient(restClient, tokenIssuer);
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
}
