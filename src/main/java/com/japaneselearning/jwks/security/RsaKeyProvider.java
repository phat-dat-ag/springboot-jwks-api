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
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
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

            if (properties.getKeys() == null || properties.getKeys().isEmpty()) {
                throw new IllegalStateException(
                        "At least one RSA key must be configured"
                );
            }

            for (RsaKeyProperties.Key config : properties.getKeys()) {
                keyIndex++;
                stage = "configuration";

                if (config == null || config.getKid() == null || config.getKid().isBlank()) {
                    throw new IllegalStateException(
                            "RSA key kid must not be blank"
                    );
                }

                if (config.getPublicKeyPath() == null || config.getPublicKeyPath().isBlank()) {
                    throw new IllegalStateException(
                            "RSA public key path must not be blank for kid: " + config.getKid()
                    );
                }

                stage = "public-key-read";

                if (config.getOwner() == null) {
                    throw new IllegalStateException("RSA key owner must be configured");
                }

                PublicKey publicKey = readPublicKey(config.getPublicKeyPath());

                stage = "public-key-validation";

                if (!(publicKey instanceof RSAPublicKey rsaPublic)) {
                    throw new IllegalStateException(
                            "Configured keys must be RSA public keys"
                    );
                }

                stage = "duplicate-identifier-validation";

                KeyMaterial previous = loaded.putIfAbsent(
                        config.getKid(),
                        new KeyMaterial(
                                config.getKid(),
                                rsaPublic,
                                config.getOwner()
                        )
                );

                if (previous != null) {
                    throw new IllegalStateException(
                            "Duplicate RSA key identifier"
                    );
                }
            }

            keys = Collections.unmodifiableMap(loaded);

            log.atInfo()
                    .addKeyValue("event", "rsa.keys.loaded")
                    .addKeyValue("key_count", keys.size())
                    .addKeyValue(
                            "durationMs",
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
            RSAPublicKey publicKey,
            RsaKeyProperties.KeyOwner owner
    ) {
    }
}
