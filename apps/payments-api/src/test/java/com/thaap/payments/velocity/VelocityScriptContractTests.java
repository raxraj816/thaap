package com.thaap.payments.velocity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VelocityScriptContractTests {

    private final VelocityScriptContract contract = new VelocityScriptContract();
    private final VelocityCompensationScriptContract compensationContract =
            new VelocityCompensationScriptContract();

    @Test
    void exactResourceBytesMatchFrozenShaAndVersion() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(contract.scriptText().getBytes(StandardCharsets.UTF_8)));

        assertThat(sha).isEqualTo(VelocityScriptContract.SHA1);
        assertThat(contract.scriptText()).startsWith("-- THAAP velocity_v1")
                .contains("local expected_version = 'velocity_v1'", "redis.call('TIME')",
                        "redis.call('ZADD', KEYS[1], 'NX', now_ms, member)");
    }

    @Test
    void exactCompensationResourceBytesMatchFrozenShaAndVersion() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(compensationContract.scriptText().getBytes(StandardCharsets.UTF_8)));

        assertThat(sha).isEqualTo(VelocityCompensationScriptContract.SHA1);
        assertThat(compensationContract.scriptText()).startsWith("-- THAAP velocity compensation")
                .contains("local expected_version = 'velocity_compensation_v1'",
                        "if current_token ~= reservation_token then",
                        "redis.call('ZREM', KEYS[1], member)");
    }

    @ParameterizedTest(name = "parses compensation result {0}")
    @MethodSource("compensationResults")
    void parsesEveryCompensationResult(String wireValue, VelocityCompensationResult expected) {
        assertThat(compensationContract.parse(wireValue)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "rejects malformed compensation result {0}")
    @MethodSource("malformedCompensationResults")
    void rejectsEveryMalformedCompensationReturnPartition(String name, String result) {
        assertThatThrownBy(() -> compensationContract.parse(result))
                .isInstanceOf(VelocityUnavailableException.class);
    }

    @Test
    void exactFiveFieldSuccessGrammarParsesCanonicalNumbersAndMode() {
        VelocityObservation observation = contract.parse(List.of(
                bytes("OK"), bytes("velocity_v1"), bytes("1720000000123"), bytes("7"), bytes("RETRY")));

        assertThat(observation).isEqualTo(new VelocityObservation("velocity_v1", 1_720_000_000_123L, 7L,
                VelocityObservation.ExecutionMode.RETRY));
    }

    @ParameterizedTest(name = "rejects result partition {0}")
    @MethodSource("malformedResults")
    void rejectsEveryMalformedReturnPartition(String name, Object result) {
        assertThatThrownBy(() -> contract.parse(result))
                .isInstanceOf(VelocityUnavailableException.class);
    }

    static Stream<Arguments> malformedResults() {
        return Stream.of(
                Arguments.of("not-list", "OK"),
                Arguments.of("short", List.of("OK", "velocity_v1")),
                Arguments.of("long", List.of("OK", "velocity_v1", "1", "0", "FIRST", "extra")),
                Arguments.of("error", List.of("ERROR", "velocity_v1", "0", "0", "INVALID_MEMBER")),
                Arguments.of("limit", List.of("LIMIT", "velocity_v1", "1", "10", "CARDINALITY_LIMIT")),
                Arguments.of("version", List.of("OK", "velocity_v2", "1", "0", "FIRST")),
                Arguments.of("negative-time", List.of("OK", "velocity_v1", "-1", "0", "FIRST")),
                Arguments.of("leading-zero", List.of("OK", "velocity_v1", "01", "0", "FIRST")),
                Arguments.of("fraction", List.of("OK", "velocity_v1", "1", "0.5", "FIRST")),
                Arguments.of("overflow", List.of("OK", "velocity_v1", "9223372036854775808", "0", "FIRST")),
                Arguments.of("mode", List.of("OK", "velocity_v1", "1", "0", "REPLAY")),
                Arguments.of("numeric-type", List.of("OK", "velocity_v1", 1L, "0", "FIRST")));
    }

    static Stream<Arguments> compensationResults() {
        return Stream.of(
                Arguments.of("REMOVED", VelocityCompensationResult.REMOVED),
                Arguments.of("NOT_FOUND", VelocityCompensationResult.NOT_FOUND),
                Arguments.of("STALE", VelocityCompensationResult.STALE));
    }

    static Stream<Arguments> malformedCompensationResults() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("empty", ""),
                Arguments.of("wrong-case", "removed"),
                Arguments.of("unknown", "ERROR"),
                Arguments.of("padded", " REMOVED"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
