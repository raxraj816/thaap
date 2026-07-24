package com.thaap.payments.review;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAnnotationRepository extends JpaRepository<ReviewAnnotation, UUID> {
}
