package com.home.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class RsaPemKeysTest {
    @Test
    void parsesPkcs8PrivateAndX509PublicKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();

        assertThat(RsaPemKeys.privateKey(pem("PRIVATE KEY", pair.getPrivate().getEncoded())).getEncoded())
            .isEqualTo(pair.getPrivate().getEncoded());
        assertThat(RsaPemKeys.publicKey(pem("PUBLIC KEY", pair.getPublic().getEncoded())).getEncoded())
            .isEqualTo(pair.getPublic().getEncoded());
        assertThatThrownBy(() -> RsaPemKeys.publicKey("not a PEM"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private String pem(String label, byte[] value) {
        return "-----BEGIN " + label + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value)
            + "\n-----END " + label + "-----\n";
    }
}
