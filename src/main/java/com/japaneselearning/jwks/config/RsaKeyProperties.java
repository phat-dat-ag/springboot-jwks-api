package com.japaneselearning.jwks.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.security.jwks")
@Getter
@Setter
public class RsaKeyProperties {

    private List<Key> keys = new ArrayList<>();

    @Getter
    @Setter
    public static class Key {

        private String kid;
        private String privateKeyPath;
        private String publicKeyPath;
        private boolean active;
    }
}