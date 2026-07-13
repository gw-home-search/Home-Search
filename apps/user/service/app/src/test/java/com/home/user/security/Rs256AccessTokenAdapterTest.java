package com.home.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Rs256AccessTokenAdapterTest {
    @Test
    void issuesCanonicalUserJwtWithoutProfileClaims(@TempDir Path directory) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        Path privateKey=directory.resolve("private.pem"),publicKey=directory.resolve("public.pem");
        Files.writeString(privateKey,pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
        Files.writeString(publicKey,pem("PUBLIC KEY",pair.getPublic().getEncoded()));
        var adapter=new Rs256AccessTokenAdapter("user-key",privateKey,publicKey,"","",Duration.ofMinutes(15),"user-service","home-search-user-api");

        var jwt=adapter.decode(adapter.issue(42L));

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("user-service");
        assertThat(jwt.getAudience()).containsExactly("home-search-user-api");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getClaims()).doesNotContainKeys("name","email","profile","providerToken");
    }

    @Test
    void rejectsWeakOverlapPublicKeyAtStartup(@TempDir Path directory) throws Exception {
        var activeGenerator = KeyPairGenerator.getInstance("RSA"); activeGenerator.initialize(2048); var active=activeGenerator.generateKeyPair();
        var overlapGenerator = KeyPairGenerator.getInstance("RSA"); overlapGenerator.initialize(1024); var overlap=overlapGenerator.generateKeyPair();
        Path privateKey=directory.resolve("private.pem"),publicKey=directory.resolve("public.pem"),overlapKey=directory.resolve("overlap.pem");
        Files.writeString(privateKey,pem("PRIVATE KEY",active.getPrivate().getEncoded()));
        Files.writeString(publicKey,pem("PUBLIC KEY",active.getPublic().getEncoded()));
        Files.writeString(overlapKey,pem("PUBLIC KEY",overlap.getPublic().getEncoded()));

        assertThatThrownBy(() -> new Rs256AccessTokenAdapter("active",privateKey,publicKey,"overlap",overlapKey.toString(),
                Duration.ofMinutes(15),"user-service","home-search-user-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid user JWT key configuration");
    }
    private static String pem(String label,byte[] value){return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(value)+"\n-----END "+label+"-----\n";}
}
