package com.home.security.user;

import com.home.security.jwt.JwtVerificationPolicy;
import com.home.security.jwt.Rs256JwtCodec;
import java.time.Clock;

public final class UserAccessTokenVerifier {
    private static final String BEARER_PREFIX = "Bearer ";
    private final UserAccessTokenPolicy policy;
    private final Rs256JwtCodec codec;

    public UserAccessTokenVerifier(UserAccessTokenPolicy policy, Clock clock) {
        if (policy == null || clock == null) throw new IllegalArgumentException("policy and clock are required");
        this.policy = policy;
        this.codec = new Rs256JwtCodec(clock);
    }

    public VerifiedUser verifyBearer(String authorizationHeader) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
                throw new UserJwtVerificationException();
            }
            String token = authorizationHeader.substring(BEARER_PREFIX.length());
            if (token.isBlank() || !token.equals(token.trim()) || token.indexOf(' ') >= 0) {
                throw new UserJwtVerificationException();
            }
            var verified = codec.verify(token, new JwtVerificationPolicy(
                policy.issuer(), policy.audience(), policy.maximumLifetime(), policy.publicKeys()::get));
            if (!"USER".equals(verified.claims().get("role"))) {
                throw new UserJwtVerificationException();
            }
            long userId = Long.parseLong(verified.subject());
            if (userId <= 0) throw new UserJwtVerificationException();
            return new VerifiedUser(userId, verified.tokenId(), verified.issuedAt(), verified.expiresAt());
        } catch (UserJwtVerificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UserJwtVerificationException();
        }
    }
}
