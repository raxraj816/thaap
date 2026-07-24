package com.thaap.payments.fraudlist;

import com.thaap.payments.customer.Customer;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.paymentmethod.PaymentMethod;
import com.thaap.payments.riskcontext.RiskContext;
import com.thaap.payments.security.CorrelationValueHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FraudListIdentityService {

    private final SensitiveValueHasher sensitiveValueHasher;
    private final CorrelationValueHasher correlationValueHasher;

    public FraudListIdentityService(SensitiveValueHasher sensitiveValueHasher,
            CorrelationValueHasher correlationValueHasher) {
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.correlationValueHasher = correlationValueHasher;
    }

    public EntryIdentity identityForEntry(UUID merchantId, FraudListIdentityType type,
            FraudListGenericNamespace namespace, String rawValue) {
        String value = requireBounded(rawValue);
        if (type == FraudListIdentityType.BIN) {
            if (!value.matches("[0-9]{6}")) {
                throw badRequest("BIN fraud-list values must contain exactly six digits");
            }
            return EntryIdentity.canonical(value);
        }
        if (type == FraudListIdentityType.COUNTRY) {
            String country = value.toUpperCase(Locale.ROOT);
            if (!country.matches("[A-Z]{2}")) {
                throw badRequest("Country fraud-list values must be two ASCII letters");
            }
            return EntryIdentity.canonical(country);
        }
        String base = switch (type) {
            case CARD_FINGERPRINT -> cardFingerprint(value);
            case IP -> riskIdentity("ip", merchantId, normalizeIp(value));
            case CUSTOMER -> parseUuid(value).toString();
            case EMAIL -> correlationValueHasher.hmacSha256Lowercase("customer.email", normalizeEmail(value));
            case EMAIL_DOMAIN -> correlationValueHasher.hmacSha256Lowercase(
                    "customer.email_domain", normalizeEmailDomain(value));
            case GENERIC_STRING -> genericBase(merchantId, namespace, value);
            case BIN, COUNTRY -> throw new IllegalStateException("Canonical list type was already handled");
        };
        return EntryIdentity.hmac(listHmac(merchantId, type, namespace, base,
                sensitiveValueHasher.currentKeyVersion()), sensitiveValueHasher.currentKeyVersion());
    }

    public List<String> candidateHmacs(UUID merchantId, FraudListIdentityType type,
            FraudListGenericNamespace namespace, String keyVersion, PaymentIntent paymentIntent,
            PaymentMethod paymentMethod, Customer customer, RiskContext riskContext) {
        List<String> bases = candidateBases(type, namespace, keyVersion,
                paymentIntent, paymentMethod, customer, riskContext);
        return bases.stream().map(base -> listHmac(merchantId, type, namespace, base, keyVersion)).toList();
    }

    public Set<String> canonicalCandidates(FraudListIdentityType type, PaymentMethod paymentMethod,
            RiskContext riskContext) {
        if (type == FraudListIdentityType.BIN) {
            return Set.of(paymentMethod.getCardBin());
        }
        if (type != FraudListIdentityType.COUNTRY) {
            return Set.of();
        }
        Set<String> countries = new LinkedHashSet<>();
        countries.add(paymentMethod.getCardCountry());
        if (riskContext != null && riskContext.getIpCountry() != null) {
            countries.add(riskContext.getIpCountry());
        }
        if (riskContext != null && riskContext.getShippingCountry() != null) {
            countries.add(riskContext.getShippingCountry());
        }
        return Set.copyOf(countries);
    }

    private List<String> candidateBases(FraudListIdentityType type, FraudListGenericNamespace namespace,
            String keyVersion,
            PaymentIntent paymentIntent, PaymentMethod paymentMethod, Customer customer, RiskContext riskContext) {
        List<String> bases = new ArrayList<>();
        switch (type) {
            case CARD_FINGERPRINT -> bases.add(paymentMethod.getCardFingerprint());
            case IP -> addIfPresent(bases,
                    riskContext == null ? null : riskContext.getIpIdentityHmac(keyVersion));
            case CUSTOMER -> bases.add(paymentIntent.getCustomerId().toString());
            case EMAIL -> bases.add(customer.getEmailHash());
            case EMAIL_DOMAIN -> addIfPresent(bases, customer.getEmailDomainHash());
            case GENERIC_STRING -> addIfPresent(bases,
                    genericStoredIdentity(namespace, riskContext, keyVersion));
            case BIN, COUNTRY -> { }
        }
        return List.copyOf(bases);
    }

    private String genericBase(UUID merchantId, FraudListGenericNamespace namespace, String rawValue) {
        if (namespace == null) {
            throw badRequest("GENERIC_STRING fraud lists require a supported namespace");
        }
        return switch (namespace) {
            case DEVICE_ID -> riskIdentity("device", merchantId, normalizeGeneric(rawValue));
            case SESSION_ID -> riskIdentity("session", merchantId, normalizeGeneric(rawValue));
        };
    }

    private String genericStoredIdentity(FraudListGenericNamespace namespace, RiskContext context,
            String keyVersion) {
        if (namespace == null || context == null) {
            return null;
        }
        return switch (namespace) {
            case DEVICE_ID -> context.getDeviceIdentityHmac(keyVersion);
            case SESSION_ID -> context.getSessionIdentityHmac(keyVersion);
        };
    }

    private String listHmac(UUID merchantId, FraudListIdentityType type,
            FraudListGenericNamespace namespace, String base, String keyVersion) {
        String namespacePart = namespace == null ? "" : ":" + namespace.name();
        return sensitiveValueHasher.hmacSha256("fraud-list:" + type.name() + namespacePart + ":v1",
                merchantId + "\u0000" + base, keyVersion);
    }

    private String cardFingerprint(String rawValue) {
        String card = rawValue.replace(" ", "");
        if (!card.matches("[0-9]{12,19}")) {
            throw badRequest("Card fingerprint list values must be a 12 to 19 digit card number");
        }
        return correlationValueHasher.hmacSha256("payment_method.card_fingerprint", card);
    }

    private String normalizeEmail(String value) {
        String email = value.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("[^@\\s]{1,64}@[^@\\s]{1,190}")) {
            throw badRequest("Email fraud-list value is invalid");
        }
        return email;
    }

    private String normalizeEmailDomain(String value) {
        String domain = value.trim().toLowerCase(Locale.ROOT);
        if (!domain.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?\\.[a-z]{2,63}")) {
            throw badRequest("Email-domain fraud-list value is invalid");
        }
        return domain;
    }

    private String normalizeGeneric(String value) {
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest("Generic fraud-list value contains a control character");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String value) {
        try {
            if (!value.contains(":")) {
                String[] octets = value.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException();
                }
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException();
                    }
                }
                return String.join(".", octets);
            }
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet6Address)) {
                throw new IllegalArgumentException();
            }
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            throw badRequest("IP fraud-list value is invalid");
        }
    }

    private String riskIdentity(String purpose, UUID merchantId, String normalizedValue) {
        return sensitiveValueHasher.hmacSha256(
                "risk-context:" + purpose + ":v1", merchantId + "\u0000" + normalizedValue);
    }

    private String requireBounded(String value) {
        if (value == null || value.isBlank() || value.length() > 320) {
            throw badRequest("Fraud-list value must contain between 1 and 320 characters");
        }
        return value.trim();
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest("Customer fraud-list value must be a UUID");
        }
    }

    private void addIfPresent(List<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record EntryIdentity(String canonicalValue, String valueHmac, String hmacKeyVersion) {
        static EntryIdentity canonical(String value) { return new EntryIdentity(value, null, null); }
        static EntryIdentity hmac(String value, String keyVersion) { return new EntryIdentity(null, value, keyVersion); }
    }
}
