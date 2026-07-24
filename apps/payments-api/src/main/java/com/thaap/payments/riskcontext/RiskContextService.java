package com.thaap.payments.riskcontext;

import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.TrafficOrigin;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskContextService {

    private final RiskContextRepository repository;
    private final SensitiveValueHasher hasher;
    private final Clock clock;
    private final RiskContextMetrics metrics;

    public RiskContextService(RiskContextRepository repository, SensitiveValueHasher hasher, Clock clock,
            RiskContextMetrics metrics) {
        this.repository = repository;
        this.hasher = hasher;
        this.clock = clock;
        this.metrics = metrics;
    }

    public RiskContext create(UUID merchantId, UUID paymentIntentId, UUID paymentAttemptId,
            RiskContextRequest request, TrafficOrigin trafficOrigin) {
        if (request == null) {
            metrics.recordObservation(false);
            return null;
        }
        Instant now = clock.instant();
        if (request.observedAt() != null && request.observedAt().isAfter(now)) {
            metrics.recordRejection("FUTURE_OBSERVED_AT");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "RiskContext observedAt is too far in the future");
        }
        String ip = normalizeIp(request.ipAddress());
        VersionedIdentity ipIdentity = identity("ip", merchantId, ip);
        VersionedIdentity userAgentIdentity = identity("user-agent", merchantId, normalize(request.userAgent()));
        VersionedIdentity deviceIdentity = identity("device", merchantId, normalize(request.deviceId()));
        VersionedIdentity sessionIdentity = identity("session", merchantId, normalize(request.sessionId()));
        RiskContext riskContext = repository.save(new RiskContext(merchantId, paymentIntentId, paymentAttemptId,
                ipIdentity.currentDigest(), userAgentIdentity.currentDigest(), deviceIdentity.currentDigest(),
                sessionIdentity.currentDigest(), ipIdentity.encodedAliases(), deviceIdentity.encodedAliases(),
                sessionIdentity.encodedAliases(),
                hasAnyIdentity(request) ? hasher.currentKeyVersion() : null,
                request, trafficOrigin, now));
        metrics.recordObservation(true);
        return riskContext;
    }

    private VersionedIdentity identity(String purpose, UUID merchantId, String value) {
        if (value == null) {
            return VersionedIdentity.empty();
        }
        List<SensitiveValueHasher.VersionedDigest> digests = hasher.hmacSha256Keyring(
                "risk-context:" + purpose + ":v1", merchantId + "\u0000" + value);
        String aliases = digests.stream()
                .map(digest -> digest.keyVersion() + "=" + digest.digest())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return new VersionedIdentity(digests.getFirst().digest(), aliases);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            metrics.recordRejection("CONTROL_CHARACTER");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "RiskContext identity contains a control character");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (!value.contains(":")) {
                String[] octets = value.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException("IPv4 must have four octets");
                }
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("IPv4 octet is invalid");
                    }
                }
                return String.join(".", octets);
            }
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("IPv6 value is invalid");
            }
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            metrics.recordRejection("INVALID_IP");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RiskContext IP address is invalid");
        }
    }

    private boolean hasAnyIdentity(RiskContextRequest request) {
        return request.ipAddress() != null || request.userAgent() != null
                || request.deviceId() != null || request.sessionId() != null;
    }

    private record VersionedIdentity(String currentDigest, String encodedAliases) {
        private static VersionedIdentity empty() {
            return new VersionedIdentity(null, null);
        }
    }
}
