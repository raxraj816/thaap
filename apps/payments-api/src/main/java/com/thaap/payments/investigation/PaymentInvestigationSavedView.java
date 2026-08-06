package com.thaap.payments.investigation;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import com.thaap.payments.workspace.SavedSearchLifecycleStatus;
import com.thaap.payments.workspace.SavedSearchVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_investigation_saved_views")
public class PaymentInvestigationSavedView {

    @Id private UUID id;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "owner_operator_id", nullable = false) private UUID ownerOperatorId;
    @Column(nullable = false, length = 80) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private SavedSearchVisibility visibility;
    @Column(name = "filter_schema_version", nullable = false, length = 32)
    private String filterSchemaVersion;
    @Column(name = "canonical_filter_json", nullable = false, columnDefinition = "TEXT")
    private String canonicalFilterJson;
    @Enumerated(EnumType.STRING) @Column(name = "sort_code", nullable = false, length = 32)
    private PaymentInvestigationSort sortCode;
    @Column(name = "visible_columns_json", nullable = false, length = 1024)
    private String visibleColumnsJson;
    @Column(name = "definition_digest", nullable = false, length = 64)
    private String definitionDigest;
    @Enumerated(EnumType.STRING) @Column(name = "lifecycle_status", nullable = false, length = 16)
    private SavedSearchLifecycleStatus lifecycleStatus;
    @Column(name = "lifecycle_version", nullable = false) private long lifecycleVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "disabled_at") private Instant disabledAt;

    protected PaymentInvestigationSavedView() {
    }

    public PaymentInvestigationSavedView(UUID merchantId, UUID ownerOperatorId, String name,
            SavedSearchVisibility visibility, String filterSchemaVersion,
            String canonicalFilterJson, PaymentInvestigationSort sortCode,
            String visibleColumnsJson, String definitionDigest, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.ownerOperatorId = ownerOperatorId;
        this.name = name;
        this.visibility = visibility;
        this.filterSchemaVersion = filterSchemaVersion;
        this.canonicalFilterJson = canonicalFilterJson;
        this.sortCode = sortCode;
        this.visibleColumnsJson = visibleColumnsJson;
        this.definitionDigest = definitionDigest;
        this.lifecycleStatus = SavedSearchLifecycleStatus.ACTIVE;
        this.lifecycleVersion = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(long expectedVersion, String name, SavedSearchVisibility visibility,
            String filterSchemaVersion, String canonicalFilterJson,
            PaymentInvestigationSort sortCode, String visibleColumnsJson,
            String definitionDigest, Instant now) {
        requireActiveVersion(expectedVersion);
        this.name = name;
        this.visibility = visibility;
        this.filterSchemaVersion = filterSchemaVersion;
        this.canonicalFilterJson = canonicalFilterJson;
        this.sortCode = sortCode;
        this.visibleColumnsJson = visibleColumnsJson;
        this.definitionDigest = definitionDigest;
        this.lifecycleVersion++;
        this.updatedAt = now;
    }

    public void disable(long expectedVersion, Instant now) {
        requireActiveVersion(expectedVersion);
        lifecycleStatus = SavedSearchLifecycleStatus.DISABLED;
        lifecycleVersion++;
        updatedAt = now;
        disabledAt = now;
    }

    private void requireActiveVersion(long expectedVersion) {
        if (lifecycleStatus != SavedSearchLifecycleStatus.ACTIVE
                || lifecycleVersion != expectedVersion) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_VIEW_STALE);
        }
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getOwnerOperatorId() { return ownerOperatorId; }
    public String getName() { return name; }
    public SavedSearchVisibility getVisibility() { return visibility; }
    public String getFilterSchemaVersion() { return filterSchemaVersion; }
    public String getCanonicalFilterJson() { return canonicalFilterJson; }
    public PaymentInvestigationSort getSortCode() { return sortCode; }
    public String getVisibleColumnsJson() { return visibleColumnsJson; }
    public String getDefinitionDigest() { return definitionDigest; }
    public SavedSearchLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public long getLifecycleVersion() { return lifecycleVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDisabledAt() { return disabledAt; }
}
