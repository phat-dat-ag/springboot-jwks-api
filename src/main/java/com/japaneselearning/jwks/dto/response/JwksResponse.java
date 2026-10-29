package com.japaneselearning.jwks.dto.response;

import java.util.List;

public record JwksResponse(
        List<JwkResponse> keys
) {
}