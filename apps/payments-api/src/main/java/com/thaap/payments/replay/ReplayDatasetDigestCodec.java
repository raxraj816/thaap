package com.thaap.payments.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.security.SecretHasher;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;

@Component
public final class ReplayDatasetDigestCodec {

    private final ObjectMapper objectMapper;

    public ReplayDatasetDigestCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String digest(Map<String, ?> values) {
        return SecretHasher.sha256(canonicalJson(values));
    }

    public String canonicalJson(Map<String, ?> values) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(new TreeMap<>(values));
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            return new String(canonical, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Replay dataset evidence could not be canonicalized", exception);
        }
    }
}
