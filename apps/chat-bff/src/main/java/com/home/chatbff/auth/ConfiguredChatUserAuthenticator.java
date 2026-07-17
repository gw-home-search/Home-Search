package com.home.chatbff.auth;

import com.home.security.jwt.RsaPemKeys;
import com.home.security.user.UserAccessTokenPolicy;
import com.home.security.user.UserAccessTokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
final class ConfiguredChatUserAuthenticator implements ChatUserAuthenticator {
    private static final long MAXIMUM_KEY_FILE_BYTES = 16 * 1024;
    private final UserAccessTokenVerifier verifier;

    ConfiguredChatUserAuthenticator(UserJwtProperties properties) {
        Map<String, PublicKey> keys = readKeys(properties.publicKeyPaths());
        this.verifier = keys.isEmpty()
                ? null
                : new UserAccessTokenVerifier(
                        new UserAccessTokenPolicy(
                                properties.issuer(), properties.audience(), properties.maximumLifetime(), keys),
                        Clock.systemUTC());
    }

    @Override
    public VerifiedChatUser authenticate(String authorizationHeader) {
        try {
            if (verifier == null) throw new AuthenticationRequiredException();
            return new VerifiedChatUser(
                    verifier.verifyBearer(authorizationHeader).userId());
        } catch (AuthenticationRequiredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthenticationRequiredException();
        }
    }

    private static Map<String, PublicKey> readKeys(String encoded) {
        if (encoded == null || encoded.isBlank()) return Map.of();
        try {
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            for (String entry : encoded.split(",")) {
                String[] pair = entry.split("=", 2);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) throw new IllegalArgumentException();
                Path path = Path.of(pair[1]);
                if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_KEY_FILE_BYTES)
                    throw new IllegalArgumentException();
                if (keys.putIfAbsent(pair[0], RsaPemKeys.publicKey(Files.readString(path))) != null)
                    throw new IllegalArgumentException();
            }
            return Map.copyOf(keys);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid chat BFF JWT public key configuration", exception);
        }
    }
}
