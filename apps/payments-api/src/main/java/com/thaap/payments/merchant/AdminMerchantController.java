package com.thaap.payments.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/merchants")
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;
    private final String bootstrapToken;

    public AdminMerchantController(MerchantRepository merchantRepository,
            @Value("${thaap.admin.bootstrap-token:}") String bootstrapToken) {
        this.merchantRepository = merchantRepository;
        this.bootstrapToken = bootstrapToken;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse createMerchant(
            @RequestHeader(name = "X-Admin-Bootstrap-Token", required = false) String providedBootstrapToken,
            @Valid @RequestBody CreateMerchantRequest request) {
        requireBootstrapToken(providedBootstrapToken);
        var apiKey = newApiKey();
        var merchant = merchantRepository.save(new Merchant(request.name(), apiKey));
        return MerchantResponse.from(merchant, apiKey);
    }

    private void requireBootstrapToken(String providedBootstrapToken) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant admin bootstrap is disabled");
        }
        if (providedBootstrapToken == null || !providedBootstrapToken.equals(bootstrapToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid merchant admin bootstrap token");
        }
    }

    private String newApiKey() {
        return "thaap_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record CreateMerchantRequest(@NotBlank String name) {
    }

    public record MerchantResponse(UUID id, String name, MerchantStatus status, String apiKeyPrefix, String apiKey) {
        static MerchantResponse from(Merchant merchant, String rawApiKey) {
            return new MerchantResponse(merchant.getId(), merchant.getName(), merchant.getStatus(),
                    merchant.getApiKeyPrefix(), rawApiKey);
        }
    }
}
