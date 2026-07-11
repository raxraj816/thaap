package com.thaap.payments.idempotency;

import com.thaap.payments.security.SensitiveValueHasher;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyConfiguration implements InitializingBean {

    private final IdempotencyRepository idempotencyRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final Clock clock;

    public IdempotencyKeyConfiguration(IdempotencyRepository idempotencyRepository,
            SensitiveValueHasher sensitiveValueHasher, Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> requiredVersions =
                idempotencyRepository.findRequiredRequestHashKeyVersions(clock.instant());
        for (String requiredVersion : requiredVersions) {
            if (!sensitiveValueHasher.canHashWith(requiredVersion)) {
                throw new IllegalStateException(
                        "Idempotency replay requires unavailable hash-pepper version "
                                + requiredVersion);
            }
        }
    }
}
