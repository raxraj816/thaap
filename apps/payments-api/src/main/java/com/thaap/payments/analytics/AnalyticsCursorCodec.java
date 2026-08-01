package com.thaap.payments.analytics;

import com.thaap.payments.security.SensitiveValueHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AnalyticsCursorCodec {

    private static final String DOMAIN = "risk.analytics.cursor";

    private final SensitiveValueHasher hasher;

    public AnalyticsCursorCodec(SensitiveValueHasher hasher) {
        this.hasher = hasher;
    }

    public String encode(UUID merchantId, UUID projectionGenerationId,
            String queryShape, Instant recordedAt, UUID paymentIntentId) {
        return encodeFields(merchantId, projectionGenerationId, queryShape,
                List.of(recordedAt.toString(), paymentIntentId.toString()));
    }

    public Cursor decode(UUID merchantId, UUID projectionGenerationId,
            String queryShape, String cursor) {
        List<String> anchors = decodeFields(merchantId, projectionGenerationId,
                queryShape, cursor, 2);
        if (anchors == null) {
            return null;
        }
        try {
            return new Cursor(Instant.parse(anchors.get(0)), UUID.fromString(anchors.get(1)));
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public String encodeCurrency(UUID merchantId, UUID projectionGenerationId,
            String queryShape, String currency) {
        return encodeFields(merchantId, projectionGenerationId, queryShape, List.of(currency));
    }

    public CurrencyCursor decodeCurrency(UUID merchantId, UUID projectionGenerationId,
            String queryShape, String cursor) {
        List<String> anchors = decodeFields(merchantId, projectionGenerationId,
                queryShape, cursor, 1);
        if (anchors == null) {
            return null;
        }
        String currency = anchors.getFirst();
        if (currency.length() != 3) {
            throw invalidCursor();
        }
        return new CurrencyCursor(currency);
    }

    public String encodeTimeSeries(UUID merchantId, UUID projectionGenerationId,
            String queryShape, Instant intervalStart, String currency) {
        return encodeFields(merchantId, projectionGenerationId, queryShape,
                List.of(intervalStart.toString(), currency));
    }

    public TimeSeriesCursor decodeTimeSeries(UUID merchantId, UUID projectionGenerationId,
            String queryShape, String cursor) {
        List<String> anchors = decodeFields(merchantId, projectionGenerationId,
                queryShape, cursor, 2);
        if (anchors == null) {
            return null;
        }
        try {
            Instant intervalStart = Instant.parse(anchors.get(0));
            String currency = anchors.get(1);
            if (currency.length() != 3) {
                throw invalidCursor();
            }
            return new TimeSeriesCursor(intervalStart, currency);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    public String encodeGeneration(UUID merchantId, UUID activePaymentGenerationId,
            String queryShape, String projectionName, long generationNumber) {
        return encodeFields(merchantId, activePaymentGenerationId, queryShape,
                List.of(projectionName, Long.toString(generationNumber)));
    }

    public GenerationCursor decodeGeneration(UUID merchantId, UUID activePaymentGenerationId,
            String queryShape, String cursor) {
        List<String> anchors = decodeFields(merchantId, activePaymentGenerationId,
                queryShape, cursor, 2);
        if (anchors == null) {
            return null;
        }
        try {
            long generationNumber = Long.parseLong(anchors.get(1));
            if (generationNumber <= 0 || anchors.get(0).isBlank()) {
                throw invalidCursor();
            }
            return new GenerationCursor(anchors.get(0), generationNumber);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private String encodeFields(UUID merchantId, UUID scopeId, String queryShape,
            List<String> anchors) {
        List<String> values = new java.util.ArrayList<>();
        values.add(merchantId.toString());
        values.add(scopeId.toString());
        values.add(queryShape);
        values.addAll(anchors);
        if (values.stream().anyMatch(value -> value == null || value.contains("\n"))) {
            throw new IllegalArgumentException("Analytics cursor fields are invalid");
        }
        String value = String.join("\n", values);
        SensitiveValueHasher.VersionedDigest digest = hasher.hmacSha256Keyring(DOMAIN, value)
                .getFirst();
        String signed = digest.keyVersion() + "\n" + value + "\n" + digest.digest();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> decodeFields(UUID merchantId, UUID scopeId, String queryShape,
            String cursor, int anchorCount) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 512) {
            throw invalidCursor();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            String[] fields = decoded.split("\n", -1);
            if (fields.length != anchorCount + 5) {
                throw invalidCursor();
            }
            UUID encodedMerchant = UUID.fromString(fields[1]);
            UUID encodedScope = UUID.fromString(fields[2]);
            if (!merchantId.equals(encodedMerchant) || !scopeId.equals(encodedScope)
                    || !queryShape.equals(fields[3])) {
                throw invalidCursor();
            }
            String value = String.join("\n",
                    java.util.Arrays.copyOfRange(fields, 1, fields.length - 1));
            String expected = hasher.hmacSha256(DOMAIN, value, fields[0]);
            if (!constantTimeEquals(expected, fields[fields.length - 1])) {
                throw invalidCursor();
            }
            return List.of(java.util.Arrays.copyOfRange(fields, 4, fields.length - 1));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private boolean constantTimeEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Analytics cursor is invalid or stale");
    }

    public record Cursor(Instant recordedAt, UUID paymentIntentId) {
    }

    public record CurrencyCursor(String currency) {
    }

    public record TimeSeriesCursor(Instant intervalStart, String currency) {
    }

    public record GenerationCursor(String projectionName, long generationNumber) {
    }
}
