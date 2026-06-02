package com.thaap.payments.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/merchants")
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;

    public AdminMerchantController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        var apiKey = newApiKey();
        var merchant = merchantRepository.save(new Merchant(request.name(), apiKey));
        return MerchantResponse.from(merchant, apiKey);
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
