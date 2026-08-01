package com.thaap.payments.platform;

record AuthenticatedPlatformOperator(PlatformOperator operator,
        PlatformOperatorCredential credential) {

    PlatformOperatorContext context() {
        return new PlatformOperatorContext(operator.getId(), operator.getOperatorCode(),
                operator.getRole(), operator.getLifecycleEpoch(), credential.getId(),
                credential.getSecretPrefix());
    }
}
