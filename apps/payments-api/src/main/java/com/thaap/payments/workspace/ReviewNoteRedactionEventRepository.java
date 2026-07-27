package com.thaap.payments.workspace;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewNoteRedactionEventRepository
        extends JpaRepository<ReviewNoteRedactionEvent, UUID> {
}
