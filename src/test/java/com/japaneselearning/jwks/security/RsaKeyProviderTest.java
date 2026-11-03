package com.japaneselearning.jwks.security;

import com.japaneselearning.jwks.config.RsaKeyProperties;
import com.japaneselearning.jwks.service.JwksService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RsaKeyProviderTest {
    @TempDir
    Path directory;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void generatePublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
    }

    @Test
    void loadsOnlyPublicFilesAndPublishesActiveAndRetainedKeys() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        var provider = provider(key("old", pem.toString(), false), key("current", pem.toString(), true));
        provider.loadKeys();

        assertThat(provider.getKeys()).containsOnlyKeys("old", "current");
        assertThat(provider.getKeys().get("current").publicKey()).isEqualTo(publicKey);
        assertThatThrownBy(() -> provider.getKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
        var jwks = new JwksService(provider).getJwks();
        assertThat(jwks.keys()).extracting(k -> k.kid()).containsExactly("old", "current");
        assertThat(jwks.keys()).allSatisfy(k -> {
            assertThat(k.kty()).isEqualTo("RSA");
            assertThat(k.n()).doesNotContain("=");
            assertThat(new java.math.BigInteger(1, Base64.getUrlDecoder().decode(k.n())))
                    .isEqualTo(publicKey.getModulus());
            assertThat(new java.math.BigInteger(1, Base64.getUrlDecoder().decode(k.e())))
                    .isEqualTo(publicKey.getPublicExponent());
        });
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(pem);
        }
    }

    @Test
    void rejectsMissingKeyList() {
        assertThatThrownBy(() -> provider().loadKeys()).isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one RSA key must be configured");
        var properties = new RsaKeyProperties();
        properties.setKeys(null);
        assertThatThrownBy(() -> new RsaKeyProvider(properties).loadKeys())
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankKid(String kid) {
        assertThatThrownBy(() -> provider(key(kid, "unused", true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("RSA key kid must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankPublicPath(String path) {
        assertThatThrownBy(() -> provider(key("key", path, true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public key path must not be blank");
    }

    @Test
    void rejectsDuplicateIdentifiers() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        assertThatThrownBy(() -> provider(key("same", pem.toString(), false),
                key("same", pem.toString(), true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Duplicate RSA key identifier");
    }

    @Test
    void preservesExactlyOneActiveKeyValidation() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        for (boolean active : List.of(false, true)) {
            assertThatThrownBy(() -> provider(key("one", pem.toString(), active),
                    key("two", pem.toString(), active)).loadKeys())
                    .isInstanceOf(IllegalStateException.class).hasMessage("Exactly one RSA key must be active");
        }
    }

    @Test
    void rejectsUnreadableAndMalformedPublicFiles() throws Exception {
        Path missing = directory.resolve("missing.pem");
        assertThatThrownBy(() -> provider(key("key", missing.toString(), true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.io.IOException.class);
        Path malformed = directory.resolve("malformed.pem");
        Files.writeString(malformed, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
        assertThatThrownBy(() -> provider(key("key", malformed.toString(), true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void rejectsNonRsaPublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        Path pem = writePublicKey("ec-public.pem", generator.generateKeyPair().getPublic());
        assertThatThrownBy(() -> provider(key("key", pem.toString(), true)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.security.GeneralSecurityException.class);
    }

    private Path writePublicKey(String name, PublicKey key) throws Exception {
        return Files.writeString(directory.resolve(name), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
    }

    private static RsaKeyProperties.Key key(String kid, String path, boolean active) {
        var key = new RsaKeyProperties.Key();
        key.setKid(kid);
        key.setPublicKeyPath(path);
        key.setActive(active);
        return key;
    }

    private static RsaKeyProvider provider(RsaKeyProperties.Key... keys) {
        var properties = new RsaKeyProperties();
        properties.setKeys(new ArrayList<>(List.of(keys)));
        return new RsaKeyProvider(properties);
    }
}
