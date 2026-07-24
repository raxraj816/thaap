package com.thaap.payments.authentication;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationNextAction(UUID challengeId, AuthenticationChallengeSource source,
        Instant expiresAt, String completionEndpoint, String clientSecret) {
}
