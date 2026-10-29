package com.japaneselearning.jwks.dto.response;

public record JwkResponse(
        String kty,
        String use,
        String alg,
        String kid,
        String n,
        String e
) {
}