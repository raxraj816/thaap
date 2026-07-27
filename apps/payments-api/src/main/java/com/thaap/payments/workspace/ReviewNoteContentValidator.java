package com.thaap.payments.workspace;

import java.net.URI;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReviewNoteContentValidator {

    private static final int MAX_CODE_POINTS = 2_000;
    private static final Pattern HTML = Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s]+", Pattern.UNICODE_CASE);
    private static final Pattern PAYMENT_SECRET = Pattern.compile(
            "(?i)\\b(?:cvv|cvc|security[ _-]?code)\\s*[:=]?\\s*\\d{3,4}\\b");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(?:sk[-_](?:(?:live|test|proj)[-_])?[A-Za-z0-9_-]{12,}"
                    + "|whsec_[A-Za-z0-9_-]{12,}"
                    + "|thaap_risk_[a-f0-9]{24,}|authc_[A-Za-z0-9_-]{20,}|Bearer\\s+[A-Za-z0-9._~-]{16,})");
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)\\b(?:api[ _-]?key|access[ _-]?token|client[ _-]?secret|password)"
                    + "\\s*[:=]\\s*[^\\s]{12,}");
    private static final Pattern DIGIT_RUN = Pattern.compile("(?:\\d[ -]?){12,18}\\d");

    public ValidatedNote validate(String input) {
        if (input == null) {
            throw invalid("Review note content is required");
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < 1 || codePoints > MAX_CODE_POINTS) {
            throw invalid("Review note content must contain between 1 and 2000 characters");
        }
        if (normalized.codePoints().anyMatch(this::prohibitedCodePoint)) {
            throw invalid("Review note content contains prohibited control characters");
        }
        if (HTML.matcher(normalized).find()) {
            throw invalid("Review note content must not contain HTML");
        }
        if (PAYMENT_SECRET.matcher(normalized).find() || SECRET.matcher(normalized).find()
                || LABELED_SECRET.matcher(normalized).find()) {
            throw invalid("Review note content appears to contain prohibited payment or secret data");
        }
        Matcher urls = URL.matcher(normalized);
        while (urls.find()) {
            if (hasEmbeddedCredentials(urls.group())) {
                throw invalid("Review note URLs must not contain embedded credentials");
            }
        }
        Matcher digitRuns = DIGIT_RUN.matcher(normalized);
        while (digitRuns.find()) {
            String digits = digitRuns.group().replaceAll("[^0-9]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                throw invalid("Review note content appears to contain a payment card number");
            }
        }
        return new ValidatedNote(normalized, codePoints);
    }

    private boolean prohibitedCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.PRIVATE_USE
                || type == Character.SURROGATE
                || type == Character.UNASSIGNED;
    }

    private boolean hasEmbeddedCredentials(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            return uri.getRawUserInfo() != null;
        } catch (IllegalArgumentException exception) {
            return rawUrl.substring(rawUrl.indexOf("://") + 3).contains("@");
        }
    }

    private boolean luhn(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ValidatedNote(String normalizedContent, int codePoints) {
    }
}
