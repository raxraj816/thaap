package com.thaap.payments.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String requestId,
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ApiErrorField> fieldErrors,
        boolean retryable,
        String recoveryAction) {
}
