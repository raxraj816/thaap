package com.thaap.payments.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class WebhookTargetPolicy {

    private final boolean localExecution;
    private final Set<String> allowedHosts;
    private final java.util.function.Function<String, InetAddress[]> hostResolver;

    @Autowired
    public WebhookTargetPolicy(Environment environment,
            @Value("${thaap.webhook.allowed-hosts:}") String allowedHosts,
            WebhookHostResolver hostResolver) {
        this.localExecution = environment.acceptsProfiles(Profiles.of("local", "test"));
        this.allowedHosts = parseAllowedHosts(allowedHosts);
        this.hostResolver = hostResolver::resolve;
    }

    WebhookTargetPolicy(boolean localExecution, Set<String> allowedHosts) {
        this(localExecution, allowedHosts, WebhookTargetPolicy::resolveDirectly);
    }

    WebhookTargetPolicy(boolean localExecution, Set<String> allowedHosts,
            java.util.function.Function<String, InetAddress[]> hostResolver) {
        this.localExecution = localExecution;
        this.allowedHosts = allowedHosts.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.hostResolver = hostResolver;
    }

    public ValidatedWebhookTarget validate(String endpointUrl) {
        URI uri = parse(endpointUrl);
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new WebhookTargetPolicyException("Webhook target cannot contain user-info or a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new WebhookTargetPolicyException("Webhook target must contain a hostname");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (localExecution) {
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new WebhookTargetPolicyException("Local webhook target must use HTTP or HTTPS");
            }
        } else {
            if (!scheme.equals("https")) {
                throw new WebhookTargetPolicyException("Non-local webhook target must use HTTPS");
            }
            if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new WebhookTargetPolicyException("Webhook target hostname is not allowlisted");
            }
        }

        InetAddress[] addresses = resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname returned no addresses",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                    new java.net.UnknownHostException(host));
        }
        for (InetAddress address : addresses) {
            if (address == null) {
                throw new WebhookTargetResolutionException(
                        "Webhook target hostname returned an invalid address answer",
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR,
                        new java.net.UnknownHostException(host));
            }
            if (localExecution) {
                if (!address.isLoopbackAddress()) {
                    throw new WebhookTargetPolicyException(
                            "Local webhook target must resolve only to loopback addresses");
                }
            } else if (!isPublic(address)) {
                throw new WebhookTargetPolicyException(
                        "Non-local webhook target resolved to a prohibited network address");
            }
        }
        int port = uri.getPort();
        if (port < 0) {
            port = scheme.equals("https") ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            throw new WebhookTargetPolicyException("Webhook target port is invalid");
        }
        return new ValidatedWebhookTarget(uri, addresses[0], port);
    }

    public boolean isLocalExecution() {
        return localExecution;
    }

    public boolean hasAllowedHosts() {
        return !allowedHosts.isEmpty();
    }

    private URI parse(String endpointUrl) {
        try {
            return new URI(endpointUrl);
        } catch (URISyntaxException exception) {
            throw new WebhookTargetPolicyException("Webhook target URL is invalid", exception);
        }
    }

    private InetAddress[] resolve(String host) {
        return hostResolver.apply(host);
    }

    private static InetAddress[] resolveDirectly(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (java.net.UnknownHostException exception) {
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname is currently unresolved",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR, exception);
        }
    }

    boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isPublicIpv6(bytes);
        }
        return false;
    }

    private boolean isPublicIpv4(byte[] address) {
        int first = unsigned(address[0]);
        int second = unsigned(address[1]);
        int third = unsigned(address[2]);

        return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0 && third == 0)
                && !(first == 192 && second == 0 && third == 2)
                && !(first == 192 && second == 88 && third == 99)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 198 && second == 51 && third == 100)
                && !(first == 203 && second == 0 && third == 113)
                && first < 224;
    }

    private boolean isPublicIpv6(byte[] address) {
        if (isIpv4MappedIpv6(address)) {
            return isPublicIpv4(Arrays.copyOfRange(address, 12, 16));
        }
        if ((unsigned(address[0]) & 0xE0) != 0x20) {
            return false;
        }
        return !hasPrefix(address, new int[] {0x20, 0x01, 0x00}, 23)
                && !hasPrefix(address, new int[] {0x20, 0x01, 0x0d, 0xb8}, 32)
                && !hasPrefix(address, new int[] {0x20, 0x02}, 16)
                && !hasPrefix(address, new int[] {0x3f, 0xfe}, 16);
    }

    private boolean isIpv4MappedIpv6(byte[] address) {
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return unsigned(address[10]) == 0xFF && unsigned(address[11]) == 0xFF;
    }

    private boolean hasPrefix(byte[] address, int[] prefixBytes, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (unsigned(address[index]) != prefixBytes[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (unsigned(address[fullBytes]) & mask) == (prefixBytes[fullBytes] & mask);
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    private Set<String> parseAllowedHosts(String configuredHosts) {
        if (configuredHosts == null || configuredHosts.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(configuredHosts.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .forEach(values::add);
        return Set.copyOf(values);
    }
}
