package com.thaap.payments.developer;

import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/developer")
public class DeveloperContractController {

    private static final String OPENAPI_RESOURCE =
            "openapi/thaap-public-api-2026-08-01.yaml";

    @Operation(operationId = "getPublicOpenApiContract")
    @GetMapping(value = "/openapi/{version}", produces = "application/yaml")
    public ResponseEntity<byte[]> openApi(@PathVariable String version) {
        requireVersion(version);
        byte[] bytes = artifact();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Thaap-OpenAPI-SHA256", digest(bytes))
                .contentType(MediaType.parseMediaType("application/yaml")).body(bytes);
    }

    @Operation(operationId = "getPublicOpenApiContractDigest")
    @GetMapping(value = "/openapi/{version}/digest", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> openApiDigest(@PathVariable String version) {
        requireVersion(version);
        byte[] bytes = artifact();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .body(digest(bytes) + "\n");
    }

    @Operation(operationId = "listApiVersions")
    @GetMapping("/api-versions")
    public VersionCatalogue versions() {
        return new VersionCatalogue(ApiVersionFilter.CURRENT_VERSION,
                List.of(new VersionEntry(ApiVersionFilter.CURRENT_VERSION,
                        LocalDate.parse(ApiVersionFilter.CURRENT_VERSION), "CURRENT", null,
                        "Absent header resolves to this initial compatible contract.")));
    }

    @Operation(operationId = "listPublicErrorCodes")
    @GetMapping("/error-catalog")
    public ErrorCatalogue errors() {
        return new ErrorCatalogue(java.util.Arrays.stream(PublicApiErrorDescriptor.values())
                .map(descriptor -> new ErrorEntry(descriptor.code(),
                        descriptor.status().value(), descriptor.retryable(),
                        descriptor.retryable()
                                ? "Mutation retries must reuse the identical Idempotency-Key and request body."
                                : "Do not retry a mutation unchanged unless recovery explicitly permits it.",
                        descriptor.recoveryAction()))
                .toList());
    }

    private void requireVersion(String version) {
        if (!ApiVersionFilter.CURRENT_VERSION.equals(version)) {
            throw new PublicApiException(PublicApiErrorDescriptor.OPENAPI_VERSION_NOT_FOUND);
        }
    }

    private byte[] artifact() {
        try (InputStream input = new ClassPathResource(OPENAPI_RESOURCE).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Committed OpenAPI artifact is unavailable", exception);
        }
    }

    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record VersionCatalogue(String defaultVersion, List<VersionEntry> versions) {
    }

    public record VersionEntry(String version, LocalDate introducedAt, String status,
            LocalDate retirementDate, String compatibility) {
    }

    public record ErrorCatalogue(List<ErrorEntry> errors) {
    }

    public record ErrorEntry(String code, int httpStatus, boolean retryable,
            String idempotencyGuidance, String recoveryAction) {
    }
}
