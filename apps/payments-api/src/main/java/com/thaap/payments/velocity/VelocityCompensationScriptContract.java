package com.thaap.payments.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class VelocityCompensationScriptContract {

    public static final String VERSION = "velocity_compensation_v1";
    public static final String RESOURCE = "risk/velocity_compensation_v1.lua";
    public static final String SHA1 = "f333eb65957b7b3beb279a3c79699ac0de758fab";

    private final byte[] scriptBytes;

    public VelocityCompensationScriptContract() {
        try {
            this.scriptBytes = new ClassPathResource(RESOURCE).getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Velocity compensation script resource is unavailable", exception);
        }
        String actualSha = sha1(scriptBytes);
        if (!SHA1.equals(actualSha)) {
            throw new IllegalStateException("Velocity compensation script SHA-1 mismatch: " + actualSha);
        }
    }

    public String scriptText() {
        return new String(scriptBytes, StandardCharsets.UTF_8);
    }

    public VelocityCompensationResult parse(String result) {
        if (result == null) {
            throw unavailable("result is missing");
        }
        try {
            return VelocityCompensationResult.valueOf(result);
        } catch (IllegalArgumentException exception) {
            throw unavailable("result is not accepted");
        }
    }

    private VelocityUnavailableException unavailable(String detail) {
        return new VelocityUnavailableException(VelocityUnavailableException.Cause.MALFORMED_RESULT,
                "Velocity compensation script result is malformed: " + detail);
    }

    private String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}
