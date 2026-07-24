package com.thaap.payments.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitScriptContractTests {
    private final RateLimitScriptContract contract = new RateLimitScriptContract();

    @Test
    void exactResourceBytesMatchFrozenShaAndVersion() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(contract.scriptText().getBytes(StandardCharsets.UTF_8)));
        assertThat(sha).isEqualTo(RateLimitScriptContract.SHA1);
        assertThat(contract.scriptText()).startsWith("-- THAAP rate_limit_fixed_window_v1")
                .contains("redis.call('TIME')", "redis.call('HSET', KEYS[1], member, '1')");
    }

    @Test
    void exactEightFieldGrammarParsesAllowedAndExceededWithoutAmbiguity() {
        RateLimitDecision allowed = contract.parse(List.of("ALLOWED", RateLimitScriptContract.VERSION,
                "1720000000123", "2", "3", "877", "RETRY", "1720000000000"));
        RateLimitDecision exceeded = contract.parse(List.of("EXCEEDED", RateLimitScriptContract.VERSION,
                "1720000000123", "3", "3", "877", "REJECTED", "1720000000000"));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.executionMode()).isEqualTo(RateLimitDecision.ExecutionMode.RETRY);
        assertThat(exceeded.allowed()).isFalse();
        assertThat(exceeded.count()).isEqualTo(3);
    }

    @Test
    void malformedRepresentationAndSemanticPartitionsFailClosed() {
        for (Object value : List.of(
                "ALLOWED",
                List.of("ALLOWED", RateLimitScriptContract.VERSION),
                List.of("ALLOWED", "v2", "1", "1", "2", "1", "FIRST", "0"),
                List.of("ALLOWED", RateLimitScriptContract.VERSION, "01", "1", "2", "1", "FIRST", "0"),
                List.of("ALLOWED", RateLimitScriptContract.VERSION, "1", "3", "2", "1", "FIRST", "0"),
                List.of("EXCEEDED", RateLimitScriptContract.VERSION, "1", "1", "2", "1", "FIRST", "0"),
                List.of("ALLOWED", RateLimitScriptContract.VERSION, 1L, "1", "2", "1", "FIRST", "0"))) {
            assertThatThrownBy(() -> contract.parse(value)).isInstanceOf(RateLimitUnavailableException.class);
        }
    }
}
