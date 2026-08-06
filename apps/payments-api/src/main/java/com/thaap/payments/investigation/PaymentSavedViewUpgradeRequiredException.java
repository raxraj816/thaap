package com.thaap.payments.investigation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PaymentSavedViewUpgradeRequiredException extends ResponseStatusException {
    public PaymentSavedViewUpgradeRequiredException() {
        super(HttpStatus.CONFLICT, "Payment saved view requires an explicit schema upgrade");
    }
}

