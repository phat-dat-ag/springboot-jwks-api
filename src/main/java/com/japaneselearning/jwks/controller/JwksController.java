package com.japaneselearning.jwks.controller;

import com.japaneselearning.jwks.dto.response.JwksResponse;
import com.japaneselearning.jwks.service.JwksService;
import com.japaneselearning.jwks.config.RsaKeyProperties.KeyOwner;
import com.japaneselearning.jwks.common.exception.BusinessException;
import com.japaneselearning.jwks.common.exception.ErrorCode;
import org.springframework.web.bind.annotation.PathVariable;
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
    @GetMapping("/api/v1/jwks/{owner}")
    public ResponseEntity<@NonNull JwksResponse> getJwks(
            @PathVariable
            String owner
    ) {
        KeyOwner keyOwner = switch (owner) {
            case "user" -> KeyOwner.USER;
            case "learning" -> KeyOwner.LEARNING;
            default -> throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        };

        return ResponseEntity.ok()
                .cacheControl(
                        CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
                ).body(jwksService.getJwks(keyOwner));
    }
}
