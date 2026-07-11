package com.thaap.payments.merchant;

import com.thaap.payments.security.SensitiveValueHasher;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class MerchantCreationKeyConfiguration implements InitializingBean {

    private final MerchantCreationRecordRepository creationRecordRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final Clock clock;

    public MerchantCreationKeyConfiguration(
            MerchantCreationRecordRepository creationRecordRepository,
            SensitiveValueHasher sensitiveValueHasher, Clock clock) {
        this.creationRecordRepository = creationRecordRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.clock = clock;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> requiredVersions =
                creationRecordRepository.findRequiredDerivationKeyVersions(clock.instant());
        for (String requiredVersion : requiredVersions) {
            if (!sensitiveValueHasher.canHashWith(requiredVersion)) {
                throw new IllegalStateException(
                        "Merchant creation replay requires unavailable hash-pepper version "
                                + requiredVersion);
            }
        }
    }
}
