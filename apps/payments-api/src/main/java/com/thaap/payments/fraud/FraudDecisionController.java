package com.thaap.payments.fraud;

import com.thaap.payments.tenant.MerchantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudDecisionController {

    private final FraudDecisionRepository fraudDecisionRepository;

    public FraudDecisionController(FraudDecisionRepository fraudDecisionRepository) {
        this.fraudDecisionRepository = fraudDecisionRepository;
    }

    @GetMapping("/fraud/decisions")
    public List<FraudDecisionResponse> listDecisions(MerchantContext merchantContext) {
        return fraudDecisionRepository.findByMerchantIdOrderByCreatedAtAsc(merchantContext.merchantId()).stream()
                .map(FraudDecisionResponse::from)
                .toList();
    }

    public record FraudDecisionResponse(UUID id, UUID merchantId, String action) {
        static FraudDecisionResponse from(FraudDecision decision) {
            return new FraudDecisionResponse(decision.getId(), decision.getMerchantId(), decision.getAction());
        }
    }
}
