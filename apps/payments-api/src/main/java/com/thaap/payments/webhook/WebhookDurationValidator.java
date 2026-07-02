package com.thaap.payments.webhook;

import java.time.Duration;

final class WebhookDurationValidator {

    private WebhookDurationValidator() {
    }

    static int checkedWholeMilliseconds(Duration duration, String label, int maximumMillis) {
        if (maximumMillis < 1) {
            throw new IllegalArgumentException("Webhook duration maximum must be positive");
        }
        if (duration == null) {
            throw invalid(label, maximumMillis, null);
        }
        long durationMillis;
        try {
            durationMillis = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw invalid(label, maximumMillis, exception);
        }
        if (durationMillis < 1 || durationMillis > maximumMillis) {
            throw invalid(label, maximumMillis, null);
        }
        if (!duration.equals(Duration.ofMillis(durationMillis))) {
            throw new IllegalArgumentException(
                    "Webhook " + label + " must use whole-millisecond precision");
        }
        return Math.toIntExact(durationMillis);
    }

    private static IllegalArgumentException invalid(
            String label, int maximumMillis, ArithmeticException cause) {
        String message = "Webhook " + label + " must be between 1 millisecond and "
                + maximumMillis + " milliseconds";
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
