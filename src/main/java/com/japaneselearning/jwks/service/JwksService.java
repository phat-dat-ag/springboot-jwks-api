package com.japaneselearning.jwks.service;

import com.japaneselearning.jwks.dto.response.JwkResponse;
import com.japaneselearning.jwks.dto.response.JwksResponse;
import com.japaneselearning.jwks.security.RsaKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@RequiredArgsConstructor
public class JwksService {
    private static final Logger log = LoggerFactory.getLogger(JwksService.class);

    private final RsaKeyProvider keyProvider;

    public JwksResponse getJwks() {
        List<JwkResponse> keys = keyProvider.getKeys()
                .values()
                .stream()
                .map(key -> {
                    var publicKey = key.publicKey();

                    return new JwkResponse(
                            "RSA",
                            "sig",
                            "RS256",
                            key.kid(),
                            encodeUnsigned(publicKey.getModulus()),
                            encodeUnsigned(publicKey.getPublicExponent())
                    );
                })
                .toList();

        log.atDebug()
                .addKeyValue("event", "jwks.prepared")
                .addKeyValue("key_count", keys.size())
                .log("JWKS public key set prepared");

        return new JwksResponse(keys);
    }

    private String encodeUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();

        // BigInteger.toByteArray() may prepend a sign byte.
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }
}
