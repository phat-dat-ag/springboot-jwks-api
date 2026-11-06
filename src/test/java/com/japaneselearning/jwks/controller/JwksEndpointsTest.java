package com.japaneselearning.jwks.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.japaneselearning.jwks.JwksApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.*;

class JwksEndpointsTest {
    @TempDir
    Path directory;

    @Test
    void parameterizedApiIsolatesKeysPreservesFormatAndRejectsUnknownOwners() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var user = (RSAPublicKey) generator.generateKeyPair().getPublic();
        var learning = (RSAPublicKey) generator.generateKeyPair().getPublic();
        Path userPem = pem("user.pem", user.getEncoded());
        Path learningPem = pem("learning.pem", learning.getEncoded());
        try (var context = new SpringApplicationBuilder(JwksApplication.class).properties(
                "server.port=0",
                "app.security.jwks.keys[0].kid=user-rsa-v1", "app.security.jwks.keys[0].owner=USER",
                "app.security.jwks.keys[0].public-key-path=" + userPem,
                "app.security.jwks.keys[1].kid=learning-rsa-v1", "app.security.jwks.keys[1].owner=LEARNING",
                "app.security.jwks.keys[1].public-key-path=" + learningPem).run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var http = HttpClient.newHttpClient();
            for (String owner : new String[]{"user", "learning"}) {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/jwks/" + owner))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control")).contains("max-age=300, public");
                var json = new ObjectMapper().readTree(response.body());
                assertThat(json.size()).isEqualTo(1);
                assertThat(json.path("keys").size()).isEqualTo(1);
                var key = json.path("keys").get(0);
                var fields = new HashSet<String>();
                key.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).containsExactlyInAnyOrder("kty", "kid", "use", "alg", "n", "e");
                assertThat(key.path("kid").asText()).isEqualTo(owner + "-rsa-v1");
                assertThat(key.path("alg").asText()).isEqualTo("RS256");
                var expected = owner.equals("user") ? user : learning;
                assertThat(new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText())))
                        .isEqualTo(expected.getModulus());
                assertThat(new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText())))
                        .isEqualTo(expected.getPublicExponent());
            }
            for (String suffix : new String[]{"/unknown", "/USER", ""}) {
                var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/jwks" + suffix))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(new ObjectMapper().readTree(response.body()).has("keys")).isFalse();
            }
        }
    }

    private Path pem(String name, byte[] key) throws Exception {
        return Files.writeString(directory.resolve(name), "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key)
                + "\n-----END PUBLIC KEY-----\n");
    }
}
