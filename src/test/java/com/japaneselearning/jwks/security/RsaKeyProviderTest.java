package com.japaneselearning.jwks.security;

import com.japaneselearning.jwks.config.RsaKeyProperties;
import com.japaneselearning.jwks.config.RsaKeyProperties.KeyOwner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
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
    void loadsOnlyPublicFilesAndPublishesEveryConfiguredKey() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Path current = writePublicKey("current.pem", generator.generateKeyPair().getPublic());
        Path next = writePublicKey("next.pem", generator.generateKeyPair().getPublic());
        var provider = provider(key("old", pem.toString()), key("current", current.toString()),
                key("next", next.toString()));
        provider.loadKeys();

        assertThat(provider.getKeys()).containsOnlyKeys("old", "current", "next");
        assertThat(provider.getKeys().get("old").publicKey()).isEqualTo(publicKey);
        assertThatThrownBy(() -> provider.getKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
        var jwks = new JwksService(provider).getJwks(RsaKeyProperties.KeyOwner.USER);
        assertThat(jwks.keys()).extracting(k -> k.kid()).containsExactly("old", "current", "next");
        assertThat(jwks.keys()).allSatisfy(k -> {
            assertThat(k.kty()).isEqualTo("RSA");
            assertThat(k.n()).doesNotContain("=");
            var expected = provider.getKeys().get(k.kid()).publicKey();
            assertThat(new java.math.BigInteger(1, Base64.getUrlDecoder().decode(k.n())))
                    .isEqualTo(expected.getModulus());
            assertThat(new java.math.BigInteger(1, Base64.getUrlDecoder().decode(k.e())))
                    .isEqualTo(expected.getPublicExponent());
        });
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactlyInAnyOrder(pem, current, next);
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
        assertThatThrownBy(() -> provider(key(kid, "unused")).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("RSA key kid must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankPublicPath(String path) {
        assertThatThrownBy(() -> provider(key("key", path)).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public key path must not be blank");
    }

    @Test
    void rejectsDuplicateIdentifiers() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        assertThatThrownBy(() -> provider(key("same", pem.toString()),
                key("same", pem.toString())).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Duplicate RSA key identifier");
    }

    @Test
    void loadsSinglePublicKeyWithoutActiveSelection() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        var provider = provider(key("one", pem.toString()));
        provider.loadKeys();
        assertThat(new JwksService(provider).getJwks(RsaKeyProperties.KeyOwner.USER).keys())
                .extracting(k -> k.kid()).containsExactly("one");
    }

    @Test
    void rejectsUnreadableAndMalformedPublicFiles() throws Exception {
        Path missing = directory.resolve("missing.pem");
        assertThatThrownBy(() -> provider(key("key", missing.toString())).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.io.IOException.class);
        Path malformed = directory.resolve("malformed.pem");
        Files.writeString(malformed, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
        assertThatThrownBy(() -> provider(key("key", malformed.toString())).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void rejectsNonRsaPublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        Path pem = writePublicKey("ec-public.pem", generator.generateKeyPair().getPublic());
        assertThatThrownBy(() -> provider(key("key", pem.toString())).loadKeys())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to load RSA keys")
                .hasCauseInstanceOf(java.security.GeneralSecurityException.class);
    }

    private Path writePublicKey(String name, PublicKey key) throws Exception {
        return Files.writeString(directory.resolve(name), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
    }

    private static RsaKeyProperties.Key key(String kid, String path) {
        var key = new RsaKeyProperties.Key();
        key.setKid(kid);
        key.setPublicKeyPath(path);
        key.setOwner(RsaKeyProperties.KeyOwner.USER);
        return key;
    }

    private static RsaKeyProvider provider(RsaKeyProperties.Key... keys) {
        var properties = new RsaKeyProperties();
        properties.setKeys(new ArrayList<>(List.of(keys)));
        return new RsaKeyProvider(properties);
    }

    @Test
    void ownersAreIsolatedAndMissingGroupsDoNotFallBack() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        var learning = key("learning-rsa-v1", pem.toString());
        learning.setOwner(KeyOwner.LEARNING);
        var loader = provider(key("user-rsa-v1", pem.toString()), learning);
        loader.loadKeys();
        var service = new JwksService(loader);
        assertThat(service.getJwks(KeyOwner.USER).keys()).extracting(k -> k.kid()).containsExactly("user-rsa-v1");
        assertThat(service.getJwks(KeyOwner.LEARNING).keys()).extracting(k -> k.kid()).containsExactly("learning-rsa-v1");
        var userOnly = provider(key("user-rsa-v1", pem.toString()));
        userOnly.loadKeys();
        assertThat(new JwksService(userOnly).getJwks(KeyOwner.LEARNING).keys()).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> service.getJwks(null));
    }

    @Test
    void missingOwnershipAndDuplicateIdsAcrossOwnersAreRejected() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        var missing = key("missing", pem.toString());
        missing.setOwner(null);
        assertThatThrownBy(() -> provider(missing).loadKeys()).hasMessage("RSA key owner must be configured");
        var learning = key("same", pem.toString());
        learning.setOwner(KeyOwner.LEARNING);
        assertThatThrownBy(() -> provider(key("same", pem.toString()), learning).loadKeys())
                .hasMessage("Duplicate RSA key identifier");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN"})
    void invalidOwnerConfigurationFailsStartup(String owner) throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        new ApplicationContextRunner().withUserConfiguration(RsaKeyProvider.class).withPropertyValues(
                "app.security.jwks.keys[0].kid=test",
                "app.security.jwks.keys[0].public-key-path=" + pem,
                "app.security.jwks.keys[0].owner=" + owner).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void indexedEnvironmentBindingSupportsOwnersAndCanonicalPublicKeyPaths() throws Exception {
        Path pem = writePublicKey("public.pem", publicKey);
        new ApplicationContextRunner().withUserConfiguration(RsaKeyProvider.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "APP_SECURITY_JWKS_KEYS_0_KID", "user-rsa-v1",
                                "APP_SECURITY_JWKS_KEYS_0_PUBLICKEYPATH", pem.toString(),
                                "APP_SECURITY_JWKS_KEYS_0_OWNER", "USER",
                                "APP_SECURITY_JWKS_KEYS_1_KID", "learning-rsa-v1",
                                "APP_SECURITY_JWKS_KEYS_1_PUBLICKEYPATH", pem.toString(),
                                "APP_SECURITY_JWKS_KEYS_1_OWNER", "LEARNING"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RsaKeyProvider.class).getKeys().get("learning-rsa-v1").owner())
                            .isEqualTo(KeyOwner.LEARNING);
                });
    }
}
