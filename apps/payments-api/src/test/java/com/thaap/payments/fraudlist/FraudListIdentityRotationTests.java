package com.thaap.payments.fraudlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.security.CorrelationValueHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudListIdentityRotationTests {

    private static final String PREVIOUS_CONFIGURATION =
            "risk-v1=ZnJhdWQtbGlzdC1vbGQtcGVwcGVy";

    @Test
    void retainedInnerAndOuterVersionsKeepIpDeviceAndSessionEntriesMatchable() {
        assertRotationMatch(FraudListIdentityType.IP, null, "203.0.113.19", "ip");
        assertRotationMatch(FraudListIdentityType.GENERIC_STRING,
                FraudListGenericNamespace.DEVICE_ID, "device-rotation", "device");
        assertRotationMatch(FraudListIdentityType.GENERIC_STRING,
                FraudListGenericNamespace.SESSION_ID, "session-rotation", "session");
    }

    private void assertRotationMatch(FraudListIdentityType type,
            FraudListGenericNamespace namespace, String rawValue, String riskPurpose) {
        UUID merchantId = UUID.randomUUID();
        SensitiveValueHasher previousHasher = new SensitiveValueHasher(
                "fraud-list-old-pepper", "risk-v1", "");
        SensitiveValueHasher rollingHasher = new SensitiveValueHasher(
                "fraud-list-new-pepper", "risk-v2", PREVIOUS_CONFIGURATION);
        CorrelationValueHasher correlationHasher = new CorrelationValueHasher("stable-correlation-pepper");
        FraudListIdentityService previousService = new FraudListIdentityService(
                previousHasher, correlationHasher);
        FraudListIdentityService rollingService = new FraudListIdentityService(
                rollingHasher, correlationHasher);
        FraudListIdentityService.EntryIdentity entry = previousService.identityForEntry(
                merchantId, type, namespace, rawValue);
        String normalized = rawValue.toLowerCase();
        String material = merchantId + "\u0000" + normalized;
        RiskContext context = mock(RiskContext.class);
        String previousInner = previousHasher.hmacSha256(
                "risk-context:" + riskPurpose + ":v1", material, "risk-v1");
        String currentInner = rollingHasher.hmacSha256(
                "risk-context:" + riskPurpose + ":v1", material, "risk-v2");
        if (type == FraudListIdentityType.IP) {
            when(context.getIpIdentityHmac("risk-v1")).thenReturn(previousInner);
            when(context.getIpIdentityHmac("risk-v2")).thenReturn(currentInner);
        } else if (namespace == FraudListGenericNamespace.DEVICE_ID) {
            when(context.getDeviceIdentityHmac("risk-v1")).thenReturn(previousInner);
            when(context.getDeviceIdentityHmac("risk-v2")).thenReturn(currentInner);
        } else {
            when(context.getSessionIdentityHmac("risk-v1")).thenReturn(previousInner);
            when(context.getSessionIdentityHmac("risk-v2")).thenReturn(currentInner);
        }

        List<String> candidates = rollingService.candidateHmacs(
                merchantId, type, namespace, entry.hmacKeyVersion(),
                mock(PaymentIntent.class), mock(PaymentMethod.class), mock(Customer.class), context);

        assertThat(entry.hmacKeyVersion()).isEqualTo("risk-v1");
        assertThat(candidates).containsExactly(entry.valueHmac());
    }
}
