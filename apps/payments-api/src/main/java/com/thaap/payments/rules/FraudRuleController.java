package com.thaap.payments.rules;

import com.thaap.payments.tenant.MerchantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudRuleController {

    private final FraudRuleRepository fraudRuleRepository;

    public FraudRuleController(FraudRuleRepository fraudRuleRepository) {
        this.fraudRuleRepository = fraudRuleRepository;
    }

    @GetMapping("/rules")
    public List<FraudRuleResponse> listRules(MerchantContext merchantContext) {
        return fraudRuleRepository.findByMerchantIdOrderByCreatedAtAsc(merchantContext.merchantId()).stream()
                .map(FraudRuleResponse::from)
                .toList();
    }

    public record FraudRuleResponse(UUID id, UUID merchantId, String name) {
        static FraudRuleResponse from(FraudRule rule) {
            return new FraudRuleResponse(rule.getId(), rule.getMerchantId(), rule.getName());
        }
    }
}
