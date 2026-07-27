package com.thaap.payments.workspace;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class ReviewNoteKeyConfiguration implements InitializingBean {

    private final ReviewNoteRepository repository;
    private final ReviewNoteCipher cipher;

    public ReviewNoteKeyConfiguration(ReviewNoteRepository repository, ReviewNoteCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override
    public void afterPropertiesSet() {
        validateRequiredKeys();
    }

    public void validateRequiredKeys() {
        if (!cipher.isConfigured()) {
            throw new IllegalStateException("Review note current encryption key is unavailable");
        }
        for (String requiredVersion : repository.findRequiredActiveKeyVersions()) {
            if (!cipher.canDecrypt(requiredVersion)) {
                throw new IllegalStateException("A live review note requires an unavailable key version");
            }
        }
    }
}
