package com.thaap.payments.webhook;

import com.thaap.payments.security.SensitiveValueHasher;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class WebhookEndpointCreationKeyConfiguration implements InitializingBean {

    private final WebhookEndpointCreationRecordRepository creationRecordRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final Clock clock;

    public WebhookEndpointCreationKeyConfiguration(
            WebhookEndpointCreationRecordRepository creationRecordRepository,
            SensitiveValueHasher sensitiveValueHasher, Clock clock) {
        this.creationRecordRepository = creationRecordRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> requiredVersions =
                creationRecordRepository.findRequiredRequestHashKeyVersions(clock.instant());
        for (String requiredVersion : requiredVersions) {
            if (!sensitiveValueHasher.canHashWith(requiredVersion)) {
                throw new IllegalStateException(
                        "Webhook endpoint replay requires unavailable hash-pepper version "
                                + requiredVersion);
            }
        }
    }
}
