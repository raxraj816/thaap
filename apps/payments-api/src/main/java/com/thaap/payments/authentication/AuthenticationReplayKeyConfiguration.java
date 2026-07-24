package com.thaap.payments.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.idempotency.IdempotencyRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationReplayKeyConfiguration {

    private final IdempotencyRepository idempotencyRepository;
    private final AuthenticationReplayCipher replayCipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuthenticationReplayKeyConfiguration(IdempotencyRepository idempotencyRepository,
            AuthenticationReplayCipher replayCipher, ObjectMapper objectMapper, Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.replayCipher = replayCipher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostConstruct
    void validateRequiredKeys() {
        for (String responseBody : idempotencyRepository.findActiveProtectedAuthenticationReplays(clock.instant())) {
            try {
                JsonNode envelope = objectMapper.readTree(responseBody);
                JsonNode format = envelope.get("format");
                JsonNode keyVersion = envelope.get("keyVersion");
                if (format == null || !"authentication-replay-v1".equals(format.asText())
                        || keyVersion == null || !replayCipher.canDecrypt(keyVersion.asText())) {
                    throw new IllegalStateException("Authentication replay key version is unavailable");
                }
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Protected authentication replay is unreadable", exception);
            }
        }
    }
}
