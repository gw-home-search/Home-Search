package com.home.security.jwt;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaPemKeys {
    private RsaPemKeys() {}

    public static PrivateKey privateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(pem, "PRIVATE KEY")));
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid PKCS#8 RSA private key", exception);
        }
    }

    public static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem, "PUBLIC KEY")));
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid X.509 RSA public key", exception);
        }
    }

    private static byte[] decode(String pem, String label) {
        if (pem == null || pem.isBlank()) throw new IllegalArgumentException("PEM is required");
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int beginIndex = pem.indexOf(begin);
        int endIndex = pem.indexOf(end);
        if (beginIndex < 0 || endIndex <= beginIndex) throw new IllegalArgumentException("PEM label is invalid");
        String base64 = pem.substring(beginIndex + begin.length(), endIndex).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
