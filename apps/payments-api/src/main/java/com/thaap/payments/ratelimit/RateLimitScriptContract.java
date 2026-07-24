package com.thaap.payments.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class RateLimitScriptContract {
    public static final String VERSION = "rate_limit_fixed_window_v1";
    public static final String RESOURCE = "risk/rate_limit_fixed_window_v1.lua";
    public static final String SHA1 = "afe84373b967e1cc728b0671b680fa5ecf36bc15";

    private final byte[] scriptBytes;

    public RateLimitScriptContract() {
        try {
            this.scriptBytes = new ClassPathResource(RESOURCE).getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Rate-limit script resource is unavailable", exception);
        }
        String actualSha = sha1(scriptBytes);
        if (!SHA1.equals(actualSha)) {
            throw new IllegalStateException("Rate-limit script SHA-1 mismatch: " + actualSha);
        }
    }

    public String scriptText() {
        return new String(scriptBytes, StandardCharsets.UTF_8);
    }

    public RateLimitDecision parse(Object rawResult) {
        if (!(rawResult instanceof List<?> values) || values.size() != 8) {
            throw malformed("result must contain exactly eight fields");
        }
        String status = text(values.get(0), "status");
        String version = text(values.get(1), "version");
        if (!VERSION.equals(version)) {
            throw unavailable(RateLimitUnavailableException.Cause.VERSION_MISMATCH,
                    "script version is not accepted");
        }
        long redisTime = unsignedLong(values.get(2), "redis time");
        long count = unsignedLong(values.get(3), "count");
        long limit = unsignedLong(values.get(4), "limit");
        long retryAfter = unsignedLong(values.get(5), "retry after");
        String modeValue = text(values.get(6), "mode");
        long windowStart = unsignedLong(values.get(7), "window start");
        if ("ERROR".equals(status)) {
            throw unavailable(RateLimitUnavailableException.Cause.SCRIPT_REJECTED,
                    "script rejected its bounded contract");
        }
        if (!"ALLOWED".equals(status) && !"EXCEEDED".equals(status)) {
            throw malformed("status is unknown");
        }
        RateLimitDecision.ExecutionMode mode;
        try {
            mode = RateLimitDecision.ExecutionMode.valueOf(modeValue);
        } catch (IllegalArgumentException exception) {
            throw malformed("execution mode is unknown");
        }
        if (("EXCEEDED".equals(status)) != (mode == RateLimitDecision.ExecutionMode.REJECTED)
                || limit < 1 || count > limit || retryAfter < 1 || retryAfter > 86_400_000L
                || windowStart > redisTime) {
            throw malformed("semantic fields are inconsistent");
        }
        return new RateLimitDecision(version, "ALLOWED".equals(status), redisTime,
                count, limit, retryAfter, mode, windowStart);
    }

    private String text(Object value, String field) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String text) {
            return text;
        }
        throw malformed(field + " is not text");
    }

    private long unsignedLong(Object value, String field) {
        String text = text(value, field);
        if (!text.matches("0|[1-9][0-9]{0,18}")) {
            throw malformed(field + " is not a canonical non-negative integer");
        }
        try {
            long parsed = Long.parseLong(text);
            if (parsed < 0) {
                throw malformed(field + " is negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw malformed(field + " exceeds signed 64-bit range");
        }
    }

    private RateLimitUnavailableException malformed(String detail) {
        return unavailable(RateLimitUnavailableException.Cause.MALFORMED_RESULT,
                "Rate-limit script result is malformed: " + detail);
    }

    private RateLimitUnavailableException unavailable(RateLimitUnavailableException.Cause cause, String detail) {
        return new RateLimitUnavailableException(cause, detail);
    }

    private String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}
