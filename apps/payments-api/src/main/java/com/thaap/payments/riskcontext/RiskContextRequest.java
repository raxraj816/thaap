package com.thaap.payments.riskcontext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record RiskContextRequest(
        @Size(max = 45) @Pattern(regexp = "[0-9A-Fa-f:.]{2,45}") String ipAddress,
        @Pattern(regexp = "[A-Z]{2}") String ipCountry,
        @Size(min = 1, max = 512) String userAgent,
        @Size(min = 1, max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String deviceId,
        @Size(min = 1, max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String sessionId,
        @Pattern(regexp = "[A-Z]{2}") String shippingCountry,
        CheckoutChannel checkoutChannel,
        @Min(0) @Max(36_500) Integer customerAccountAgeDays,
        @Min(0) @Max(525_600) Integer loginAgeMinutes,
        @Min(0) @Max(10_000) Integer botScoreBps,
        CaptchaOutcome captchaOutcome,
        Instant observedAt) {
}
