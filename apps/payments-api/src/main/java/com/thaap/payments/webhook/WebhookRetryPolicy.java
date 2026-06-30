package com.thaap.payments.webhook;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class WebhookRetryPolicy {

    public static final int MAX_ATTEMPTS = 5;

    public Duration delayBeforeNextAttempt(int failedAttemptNumber) {
        return switch (failedAttemptNumber) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(30);
            case 4 -> Duration.ofHours(2);
            default -> throw new IllegalArgumentException(
                    "No retry delay exists after attempt " + failedAttemptNumber);
        };
    }

    public boolean isExhausted(int failedAttemptNumber) {
        return failedAttemptNumber >= MAX_ATTEMPTS;
    }
}
