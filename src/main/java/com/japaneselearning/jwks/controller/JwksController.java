package com.japaneselearning.jwks.controller;

import com.japaneselearning.jwks.dto.response.JwksResponse;
import com.japaneselearning.jwks.service.JwksService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwksService jwksService;

    @Operation(
            summary = "Retrieve the JSON Web Key Set",
            description =
                    "Returns RSA public signing keys in RFC 7517 format. "
                            + "Private key material is never included. "
                            + "The response is publicly cacheable for five minutes."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Public signing keys",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = JwksResponse.class)
            )
    )
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<@NonNull JwksResponse> getJwks() {
        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
                ).body(jwksService.getJwks());
    }
}