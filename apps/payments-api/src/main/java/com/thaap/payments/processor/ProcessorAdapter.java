package com.thaap.payments.processor;

public interface ProcessorAdapter {

    ProcessorAuthorizationResult authorize(ProcessorAuthorizationRequest request);
}
