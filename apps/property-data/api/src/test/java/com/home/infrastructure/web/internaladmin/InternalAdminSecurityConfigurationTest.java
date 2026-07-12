package com.home.infrastructure.web.internaladmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

class InternalAdminSecurityConfigurationTest {
    @TempDir Path directory;

    @Test
    @DisplayName("여러 public key를 kid로 로드하고 중복 kid를 거부한다")
    void loadsMultiplePublicKeysByKeyIdAndRejectsDuplicateIds() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Path oldKey = write("old.pub", generator.generateKeyPair().getPublic().getEncoded());
        Path activeKey = write("active.pub", generator.generateKeyPair().getPublic().getEncoded());

        assertThat(InternalAdminSecurityConfiguration.loadPublicKeys(
            "old-key=" + oldKey + ",active-key=" + activeKey)).containsOnlyKeys("old-key", "active-key");
        assertThatThrownBy(() -> InternalAdminSecurityConfiguration.loadPublicKeys(
            "active-key=" + activeKey + ",active-key=" + oldKey)).isInstanceOf(IllegalArgumentException.class);
    }

    private Path write(String fileName, byte[] encoded) throws Exception {
        Path path = directory.resolve(fileName);
        Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
            + "\n-----END PUBLIC KEY-----\n");
        return path;
    }
}
