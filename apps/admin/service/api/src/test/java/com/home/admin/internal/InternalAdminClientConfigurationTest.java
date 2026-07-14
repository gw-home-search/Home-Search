package com.home.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.home.admin.config.InternalAdminClientProperties;
import com.home.admin.config.InternalAdminJwtProperties;
import com.home.security.jwt.Rs256JwtCodec;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternalAdminClientConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsPkcs8PrivateKeyWithoutExposingItToPropertyClientConfiguration() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path privateKeyPath = tempDir.resolve("admin-private.pem");
        Files.writeString(privateKeyPath, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        var configuration = new InternalAdminClientConfiguration();
        var issuer = configuration.internalAdminTokenIssuer(
                new Rs256JwtCodec(Clock.systemUTC()),
                new InternalAdminJwtProperties(
                        true,
                        "admin-service",
                        "property-data-admin",
                        Duration.ofSeconds(60),
                        "active-2026-07",
                        privateKeyPath.toString()));

        assertThat(issuer).isNotNull();
        assertThat(new InternalAdminClientProperties(
                                true,
                                URI.create("http://property-data:8080"),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(10))
                        .isValid())
                .isTrue();
    }

    @Test
    void rejectsOversizedPrivateKeyAndUnsafeBaseUrls() throws Exception {
        Path oversized = tempDir.resolve("oversized.pem");
        Files.writeString(oversized, "x".repeat(16 * 1024 + 1));
        var configuration = new InternalAdminClientConfiguration();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.internalAdminTokenIssuer(
                        new Rs256JwtCodec(Clock.systemUTC()),
                        new InternalAdminJwtProperties(
                                true, "issuer", "audience", Duration.ofSeconds(60), "kid", oversized.toString())));
        for (String unsafe : java.util.List.of(
                "file:///tmp/property", "http://user:pass@property-data", "http://property-data/path")) {
            assertThat(new InternalAdminClientProperties(
                                    true, URI.create(unsafe), Duration.ofSeconds(2), Duration.ofSeconds(10))
                            .isValid())
                    .isFalse();
        }
    }

    private String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----\n";
    }
}
