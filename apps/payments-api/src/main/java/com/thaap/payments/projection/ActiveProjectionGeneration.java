package com.thaap.payments.projection;

import java.util.UUID;

public record ActiveProjectionGeneration(
        UUID definitionId,
        UUID generationId,
        long generationNumber,
        String handlerVersion) {
}
