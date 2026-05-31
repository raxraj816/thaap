package com.thaap.payments.review;

import com.thaap.payments.tenant.MerchantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewCaseController {

    private final ReviewCaseRepository reviewCaseRepository;

    public ReviewCaseController(ReviewCaseRepository reviewCaseRepository) {
        this.reviewCaseRepository = reviewCaseRepository;
    }

    @GetMapping("/reviews")
    public List<ReviewCaseResponse> listReviews(MerchantContext merchantContext) {
        return reviewCaseRepository.findByMerchantIdOrderByCreatedAtAsc(merchantContext.merchantId()).stream()
                .map(ReviewCaseResponse::from)
                .toList();
    }

    public record ReviewCaseResponse(UUID id, UUID merchantId, String status) {
        static ReviewCaseResponse from(ReviewCase reviewCase) {
            return new ReviewCaseResponse(reviewCase.getId(), reviewCase.getMerchantId(), reviewCase.getStatus());
        }
    }
}
