package com.thaap.payments.incident;

import com.thaap.payments.risk.RiskOperator;
import com.thaap.payments.risk.RiskOperatorRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "card_testing_incident_resolutions")
public class CardTestingIncidentResolution {
    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "incident_id", nullable = false) private UUID incidentId;
    @Column(name = "actor_operator_id", nullable = false) private UUID actorOperatorId;
    @Enumerated(EnumType.STRING) @Column(name = "actor_role", nullable = false, length = 32)
    private RiskOperatorRole actorRole;
    @Enumerated(EnumType.STRING) @Column(name = "mitigation_code", nullable = false, length = 64)
    private IncidentMitigationCode mitigationCode;
    @Column(name = "before_status", nullable = false, length = 16) private String beforeStatus;
    @Column(name = "after_status", nullable = false, length = 16) private String afterStatus;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected CardTestingIncidentResolution() {}

    public CardTestingIncidentResolution(CardTestingIncident incident, RiskOperator actor,
            IncidentMitigationCode mitigationCode, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = incident.getMerchantId();
        this.incidentId = incident.getId();
        this.actorOperatorId = actor.getId();
        this.actorRole = actor.getRole();
        this.mitigationCode = mitigationCode;
        this.beforeStatus = "OPEN";
        this.afterStatus = "RESOLVED";
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public IncidentMitigationCode getMitigationCode() { return mitigationCode; }
    public Instant getCreatedAt() { return createdAt; }
}
