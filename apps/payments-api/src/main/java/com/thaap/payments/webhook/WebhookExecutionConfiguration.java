package com.thaap.payments.webhook;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class WebhookExecutionConfiguration {

    private final boolean executionEnabled;
    private final WebhookSecretCipher secretCipher;
    private final WebhookTargetPolicy targetPolicy;
    private final WebhookEndpointRepository endpointRepository;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    @Autowired
    public WebhookExecutionConfiguration(
            @Value("${thaap.webhook.execution-enabled:false}") boolean executionEnabled,
            WebhookSecretCipher secretCipher, WebhookTargetPolicy targetPolicy,
            WebhookEndpointRepository endpointRepository, Clock clock,
            @Value("${thaap.webhook.lease-duration:30s}") Duration leaseDuration,
            @Value("${thaap.webhook.connect-timeout:2s}") Duration connectTimeout,
            @Value("${thaap.webhook.request-timeout:5s}") Duration requestTimeout) {
        this.executionEnabled = executionEnabled;
        this.secretCipher = secretCipher;
        this.targetPolicy = targetPolicy;
        this.endpointRepository = endpointRepository;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
    }

    WebhookExecutionConfiguration(boolean executionEnabled,
            WebhookSecretCipher secretCipher, WebhookTargetPolicy targetPolicy) {
        this.executionEnabled = executionEnabled;
        this.secretCipher = secretCipher;
        this.targetPolicy = targetPolicy;
        this.endpointRepository = null;
        this.clock = Clock.systemUTC();
        this.leaseDuration = Duration.ofSeconds(30);
        this.connectTimeout = Duration.ofSeconds(2);
        this.requestTimeout = Duration.ofSeconds(5);
    }

    @PostConstruct
    void validate() {
        if (endpointRepository != null) {
            for (String persistedVersion : endpointRepository.findRequiredSecretKeyVersions(
                    clock.instant())) {
                if (!secretCipher.canDecryptKeyVersion(persistedVersion)) {
                    throw new IllegalStateException(
                            "Webhook administration or execution cannot decrypt required persisted key version "
                                    + persistedVersion);
                }
            }
        }
        if (!executionEnabled) {
            return;
        }
        if (!secretCipher.isConfigured()) {
            throw new IllegalStateException(
                    "Webhook execution requires thaap.webhook.secret-encryption-key");
        }
        if (!targetPolicy.isLocalExecution() && !targetPolicy.hasAllowedHosts()) {
            throw new IllegalStateException(
                    "Non-local webhook execution requires thaap.webhook.allowed-hosts");
        }
        Duration maximumAttemptDuration = connectTimeout.plus(requestTimeout);
        if (leaseDuration.compareTo(maximumAttemptDuration) <= 0) {
            throw new IllegalStateException(
                    "Webhook lease duration must exceed connect timeout plus request timeout");
        }
    }
}
