package com.home.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import com.home.security.jwt.Rs256JwtCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternalAdminClientConfigurationTest {
    @TempDir Path tempDir;

    @Test
    void loadsPkcs8PrivateKeyWithoutExposingItToPropertyClientConfiguration() throws Exception {
        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        Path privateKeyPath = tempDir.resolve("admin-private.pem");
        Files.writeString(privateKeyPath, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        var configuration = new InternalAdminClientConfiguration();
        var issuer = configuration.internalAdminTokenIssuer(new Rs256JwtCodec(Clock.systemUTC()),
            privateKeyPath, "active-2026-07", "admin-service", "property-data-admin", Duration.ofSeconds(60));

        assertThat(issuer).isNotNull();
        assertThat(configuration.validatedBaseUri("http://property-data:8080").toString())
            .isEqualTo("http://property-data:8080");
    }

    @Test
    void rejectsOversizedPrivateKeyAndUnsafeBaseUrls() throws Exception {
        Path oversized = tempDir.resolve("oversized.pem");
        Files.writeString(oversized, "x".repeat(16 * 1024 + 1));
        var configuration = new InternalAdminClientConfiguration();

        assertThatIllegalArgumentException().isThrownBy(() -> configuration.internalAdminTokenIssuer(
            new Rs256JwtCodec(Clock.systemUTC()), oversized, "kid", "issuer", "audience", Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.validatedBaseUri("file:///tmp/property"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.validatedBaseUri("http://user:pass@property-data"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuration.validatedBaseUri("http://property-data/path"));
    }

    private String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
            + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
            + "\n-----END " + label + "-----\n";
    }
}
