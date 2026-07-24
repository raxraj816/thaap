package com.thaap.payments.riskcontext;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskContextRepository extends JpaRepository<RiskContext, UUID> {

    @Query("SELECT DISTINCT context.identityKeyVersion FROM RiskContext context "
            + "WHERE context.identityKeyVersion IS NOT NULL")
    List<String> findRequiredIdentityKeyVersions();
}
