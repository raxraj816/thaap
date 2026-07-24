package com.thaap.payments.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class VelocityScriptContract {

    public static final String VERSION = "velocity_v1";
    public static final String RESOURCE = "risk/velocity_v1.lua";
    public static final String SHA1 = "10bde6dc25904bcbce276ecc99f44e6bbaeeb53b";

    private final byte[] scriptBytes;

    public VelocityScriptContract() {
        try {
            this.scriptBytes = new ClassPathResource(RESOURCE).getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Velocity script resource is unavailable", exception);
        }
        String actualSha = sha1(scriptBytes);
        if (!SHA1.equals(actualSha)) {
            throw new IllegalStateException("Velocity script SHA-1 mismatch: " + actualSha);
        }
    }

    public String scriptText() {
        return new String(scriptBytes, StandardCharsets.UTF_8);
    }

    public VelocityObservation parse(Object rawResult) {
        if (!(rawResult instanceof List<?> values) || values.size() != 5) {
            throw malformed("result must contain exactly five fields");
        }
        String status = text(values.get(0), "status");
        String version = text(values.get(1), "version");
        String redisTime = text(values.get(2), "redis time");
        String count = text(values.get(3), "count");
        String mode = text(values.get(4), "mode");
        if (!VERSION.equals(version)) {
            throw unavailable(VelocityUnavailableException.Cause.VERSION_MISMATCH,
                    "script version is not accepted");
        }
        if ("ERROR".equals(status) || "LIMIT".equals(status)) {
            VelocityUnavailableException.Cause cause;
            try {
                cause = VelocityUnavailableException.Cause.valueOf(mode);
            } catch (IllegalArgumentException exception) {
                throw malformed("error cause is unknown");
            }
            throw unavailable(cause, "script returned " + status);
        }
        if (!"OK".equals(status)) {
            throw malformed("status is unknown");
        }
        long parsedTime = unsignedLong(redisTime, "redis time");
        long parsedCount = unsignedLong(count, "count");
        VelocityObservation.ExecutionMode parsedMode;
        try {
            parsedMode = VelocityObservation.ExecutionMode.valueOf(mode);
        } catch (IllegalArgumentException exception) {
            throw malformed("execution mode is unknown");
        }
        return new VelocityObservation(version, parsedTime, parsedCount, parsedMode);
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

    private long unsignedLong(String value, String field) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw malformed(field + " is not a canonical non-negative integer");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw malformed(field + " is negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw malformed(field + " exceeds signed 64-bit range");
        }
    }

    private VelocityUnavailableException malformed(String detail) {
        return unavailable(VelocityUnavailableException.Cause.MALFORMED_RESULT,
                "Velocity script result is malformed: " + detail);
    }

    private VelocityUnavailableException unavailable(VelocityUnavailableException.Cause cause, String detail) {
        return new VelocityUnavailableException(cause, detail);
    }

    private String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}
