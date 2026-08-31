package com.tailcatmesh.server.common;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Generates and hashes control-plane secrets; raw secrets are never persisted. */
@Component
public final class CredentialHasher {

    private static final int RANDOM_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();
    private final PasswordEncoder passwordEncoder;

    public CredentialHasher(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String newSecret(String prefix) {
        byte[] value = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(value);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String hash(String raw) {
        return passwordEncoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return raw != null && hash != null && passwordEncoder.matches(raw, hash);
    }

    public String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
