package com.thaap.payments.authentication;

import com.thaap.payments.security.SensitiveValueHasher;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationChallengeKeyConfiguration {

    private final AuthenticationChallengeRepository challengeRepository;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final Clock clock;

    public AuthenticationChallengeKeyConfiguration(AuthenticationChallengeRepository challengeRepository,
            SensitiveValueHasher sensitiveValueHasher, Clock clock) {
        this.challengeRepository = challengeRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.clock = clock;
    }

    @PostConstruct
    void validateRequiredKeys() {
        for (String keyVersion : challengeRepository.findRequiredSecretKeyVersions(clock.instant())) {
            if (!sensitiveValueHasher.canHashWith(keyVersion)) {
                throw new IllegalStateException(
                        "Authentication challenge key version is unavailable: " + keyVersion);
            }
        }
    }
}
