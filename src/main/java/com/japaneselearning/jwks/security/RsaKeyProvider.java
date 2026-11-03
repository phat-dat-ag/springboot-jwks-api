package com.japaneselearning.jwks.security;

import com.japaneselearning.jwks.config.RsaKeyProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RsaKeyProperties.class)
@Getter
public class RsaKeyProvider {
    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private final RsaKeyProperties properties;

    private Map<String, KeyMaterial> keys = Map.of();

    @PostConstruct
    public void loadKeys() {
        long started = System.nanoTime();
        String stage = "configuration";
        int keyIndex = -1;

        log.atInfo()
                .addKeyValue("event", "rsa.keys.loading")
                .log("RSA key loading started");

        try {
            Map<String, KeyMaterial> loaded = new LinkedHashMap<>();
            int activeCount = 0;

            if (properties.getKeys() == null || properties.getKeys().isEmpty()) {
                throw new IllegalStateException(
                        "At least one RSA key must be configured"
                );
            }

            for (RsaKeyProperties.Key config : properties.getKeys()) {
                keyIndex++;
                stage = "configuration";

                if (config.getKid() == null || config.getKid().isBlank()) {
                    throw new IllegalStateException(
                            "RSA key kid must not be blank"
                    );
                }

                if (config.getPrivateKeyPath() == null || config.getPrivateKeyPath().isBlank()) {
                    throw new IllegalStateException(
                            "RSA private key path must not be blank for kid: " + config.getKid()
                    );
                }

                if (config.getPublicKeyPath() == null || config.getPublicKeyPath().isBlank()) {
                    throw new IllegalStateException(
                            "RSA public key path must not be blank for kid: " + config.getKid()
                    );
                }

                if (config.isActive()) {
                    activeCount++;
                }

                stage = "private-key-read";
                PrivateKey privateKey = readPrivateKey(config.getPrivateKeyPath());

                stage = "public-key-read";
                PublicKey publicKey = readPublicKey(config.getPublicKeyPath());

                stage = "key-pair-validation";

                if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivate)
                        || !(publicKey instanceof RSAPublicKey rsaPublic)
                ) {
                    throw new IllegalStateException(
                            "Configured keys must be RSA CRT private "
                                    + "and RSA public keys"
                    );
                }

                if (!rsaPrivate.getModulus().equals(rsaPublic.getModulus())
                        || !rsaPrivate.getPublicExponent().equals(rsaPublic.getPublicExponent())
                ) {
                    throw new IllegalStateException(
                            "RSA public/private key mismatch"
                    );
                }

                stage = "duplicate-identifier-validation";

                KeyMaterial previous = loaded.putIfAbsent(
                        config.getKid(),
                        new KeyMaterial(
                                config.getKid(),
                                rsaPrivate,
                                rsaPublic,
                                config.isActive()
                        )
                );

                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate RSA key identifier"
                    );
                }
            }

            stage = "active-key-validation";

            if (activeCount != 1) {
                throw new IllegalStateException(
                        "Exactly one RSA key must be active"
                );
            }

            keys = Collections.unmodifiableMap(loaded);

            log.atInfo()
                    .addKeyValue("event", "rsa.keys.loaded")
                    .addKeyValue("key_count", keys.size())
                    .addKeyValue("active_count", activeCount)
                    .addKeyValue(
                            "elapsed_ms",
                            (System.nanoTime() - started) / 1_000_000
                    )
                    .log("RSA key loading completed");
        } catch (IOException | GeneralSecurityException exception) {
            log.atError()
                    .addKeyValue("event", "rsa.keys.failed")
                    .addKeyValue("stage", stage)
                    .addKeyValue("key_index", keyIndex)
                    .setCause(exception).log("RSA key loading failed");

            throw new IllegalStateException(
                    "Failed to load RSA keys", exception
            );
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "rsa.keys.failed")
                    .addKeyValue("stage", stage)
                    .addKeyValue("key_index", keyIndex)
                    .setCause(exception).log("RSA key loading failed");

            throw exception;
        }
    }

    private PrivateKey readPrivateKey(String filePath)
            throws IOException, GeneralSecurityException {

        String pem = Files.readString(Path.of(filePath));
        String content = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] bytes = Base64.getDecoder().decode(content);

        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private PublicKey readPublicKey(String filePath)
            throws IOException, GeneralSecurityException {

        String pem = Files.readString(Path.of(filePath));
        String content = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] bytes = Base64.getDecoder().decode(content);

        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(bytes));
    }

    public record KeyMaterial(
            String kid,
            RSAPrivateCrtKey privateKey,
            RSAPublicKey publicKey,
            boolean active
    ) {
    }
}
