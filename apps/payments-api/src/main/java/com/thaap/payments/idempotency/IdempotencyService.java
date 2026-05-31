package com.thaap.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.tenant.MerchantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> ResponseEntity<T> execute(MerchantContext merchantContext, String key, String operation, Object request,
            Class<T> responseType, Supplier<ResponseEntity<T>> operationSupplier) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header");
        }

        var requestHash = requestHash(operation, request);
        var existing = idempotencyRepository.findByMerchantIdAndKey(merchantContext.merchantId(), key);
        if (existing.isPresent()) {
            var record = existing.get();
            if (!record.getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key was already used with a different request");
            }
            if (record.getStatus() != IdempotencyStatus.COMPLETED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in progress");
            }
            return replay(record, responseType);
        }

        var record = idempotencyRepository.save(new IdempotencyRecord(merchantContext.merchantId(), key, requestHash));
        var response = operationSupplier.get();
        record.complete(response.getStatusCode().value(), write(response.getBody()));
        return response;
    }

    private <T> ResponseEntity<T> replay(IdempotencyRecord record, Class<T> responseType) {
        try {
            var response = objectMapper.readValue(record.getResponseBody(), responseType);
            return ResponseEntity.status(record.getHttpStatus()).body(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is not readable", exception);
        }
    }

    private String requestHash(String operation, Object request) {
        return sha256(operation + ":" + write(request));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Request or response could not be serialized", exception);
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
