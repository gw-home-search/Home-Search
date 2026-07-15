package com.home.user.security;

import com.home.application.auth.port.AccessTokenIssuer;
import com.home.security.jwt.JwtIssueRequest;
import com.home.security.jwt.Rs256JwtCodec;
import com.home.security.jwt.RsaPemKeys;
import com.home.security.user.UserAccessTokenPolicy;
import com.home.security.user.UserAccessTokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class Rs256AccessTokenAdapter implements AccessTokenIssuer, JwtDecoder {
    public static final String ISSUER = "user-service", AUDIENCE = "home-search-user-api";
    private static final long MAXIMUM_KEY_FILE_BYTES = 16 * 1024;
    private final Rs256JwtCodec codec = new Rs256JwtCodec(Clock.systemUTC());
    private final String activeKid;
    private final PrivateKey privateKey;
    private final Map<String, PublicKey> publicKeys;
    private final Duration lifetime;
    private final UserAccessTokenVerifier verifier;

    public Rs256AccessTokenAdapter(
            @Value("${home.jwt.active-kid}") String activeKid,
            @Value("${home.jwt.private-key-path}") Path privatePath,
            @Value("${home.jwt.active-public-key-path}") Path publicPath,
            @Value("${home.jwt.overlap-kid:}") String overlapKid,
            @Value("${home.jwt.overlap-public-key-path:}") String overlapPath,
            @Value("${home.jwt.lifetime:15m}") Duration lifetime,
            @Value("${home.jwt.issuer:user-service}") String issuer,
            @Value("${home.jwt.audience:home-search-user-api}") String audience) {
        try {
            if (!ISSUER.equals(issuer) || !AUDIENCE.equals(audience) || lifetime.compareTo(Duration.ofMinutes(15)) != 0)
                throw new IllegalStateException("canonical JWT policy mismatch");
            this.activeKid = required(activeKid);
            this.lifetime = lifetime;
            this.privateKey = RsaPemKeys.privateKey(readKey(privatePath));
            PublicKey active = RsaPemKeys.publicKey(readKey(publicPath));
            validateRsa(privateKey);
            validateRsa(active);
            var keys = new HashMap<String, PublicKey>();
            keys.put(this.activeKid, active);
            if (!overlapKid.isBlank() || !overlapPath.isBlank()) {
                if (overlapKid.isBlank() || overlapPath.isBlank() || overlapKid.equals(activeKid))
                    throw new IllegalStateException("invalid overlap key");
                PublicKey overlap = RsaPemKeys.publicKey(readKey(Path.of(overlapPath)));
                validateRsa(overlap);
                keys.put(overlapKid, overlap);
            }
            this.publicKeys = Map.copyOf(keys);
            this.verifier = new UserAccessTokenVerifier(
                    new UserAccessTokenPolicy(ISSUER, AUDIENCE, Duration.ofMinutes(15), this.publicKeys),
                    Clock.systemUTC());
            byte[] proof = "home-search-user-key-pair".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(proof);
            byte[] signature = signer.sign();
            var verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(active);
            verifier.update(proof);
            if (!verifier.verify(signature)) throw new IllegalStateException("private and public keys do not match");
        } catch (Exception exception) {
            throw new IllegalStateException("invalid user JWT key configuration", exception);
        }
    }

    @Override
    public String issue(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return codec.issue(
                new JwtIssueRequest(
                        ISSUER,
                        AUDIENCE,
                        Long.toString(userId),
                        UUID.randomUUID().toString(),
                        activeKid,
                        lifetime,
                        Map.of("role", "USER")),
                privateKey);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        try {
            var verified = verifier.verifyBearer("Bearer " + token);
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .issuer(ISSUER)
                    .audience(java.util.List.of(AUDIENCE))
                    .subject(Long.toString(verified.userId()))
                    .issuedAt(verified.issuedAt())
                    .expiresAt(verified.expiresAt())
                    .claim("jti", verified.tokenId())
                    .claim("role", "USER")
                    .build();
        } catch (Exception exception) {
            throw new BadJwtException("invalid access token", exception);
        }
    }

    private static String readKey(Path path) throws Exception {
        if (path == null || !Files.isRegularFile(path) || Files.size(path) > MAXIMUM_KEY_FILE_BYTES)
            throw new IllegalArgumentException("invalid key file");
        return Files.readString(path);
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static void validateRsa(java.security.Key key) {
        if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < 2048)
            throw new IllegalArgumentException("RSA key must be at least 2048 bit");
    }
}
