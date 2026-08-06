package com.thaap.payments.api;

import org.springframework.web.server.ResponseStatusException;

public final class PublicApiException extends ResponseStatusException {

    private final PublicApiErrorDescriptor descriptor;

    public PublicApiException(PublicApiErrorDescriptor descriptor) {
        super(descriptor.status(), descriptor.safeMessage());
        this.descriptor = descriptor;
    }

    /**
     * Preserves a domain-specific reason for in-process diagnostics and legacy
     * tests while the HTTP boundary renders only the reviewed descriptor text.
     */
    public PublicApiException(PublicApiErrorDescriptor descriptor, String reason) {
        super(descriptor.status(), reason);
        this.descriptor = descriptor;
    }

    public PublicApiException(PublicApiErrorDescriptor descriptor, String reason,
            Throwable cause) {
        super(descriptor.status(), reason, cause);
        this.descriptor = descriptor;
    }

    public PublicApiErrorDescriptor descriptor() {
        return descriptor;
    }
}
