package com.thaap.payments.workspace;

import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.api.PublicApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Entity
@Table(name = "review_saved_searches")
public class ReviewSavedSearch {

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
    private ReviewQueueSort sortCode;
    @Column(name = "visible_columns_json", nullable = false, length = 512)
    private String visibleColumnsJson;
    @Column(name = "definition_digest", nullable = false, length = 64)
    private String definitionDigest;
    @Enumerated(EnumType.STRING) @Column(name = "lifecycle_status", nullable = false, length = 16)
    private SavedSearchLifecycleStatus lifecycleStatus;
    @Column(name = "lifecycle_version", nullable = false) private long lifecycleVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "disabled_at") private Instant disabledAt;

    protected ReviewSavedSearch() {
    }

    public ReviewSavedSearch(UUID merchantId, UUID ownerOperatorId, String name,
            SavedSearchVisibility visibility, String filterSchemaVersion,
            String canonicalFilterJson, ReviewQueueSort sortCode, String visibleColumnsJson,
            String definitionDigest, Instant now) {
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
            String filterSchemaVersion, String canonicalFilterJson, ReviewQueueSort sortCode,
            String visibleColumnsJson, String definitionDigest, Instant now) {
        requireActiveAndVersion(expectedVersion);
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
        requireActiveAndVersion(expectedVersion);
        this.lifecycleStatus = SavedSearchLifecycleStatus.DISABLED;
        this.lifecycleVersion++;
        this.updatedAt = now;
        this.disabledAt = now;
    }

    private void requireActiveAndVersion(long expectedVersion) {
        if (lifecycleStatus != SavedSearchLifecycleStatus.ACTIVE) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_SEARCH_DISABLED, "Saved search is disabled");
        }
        if (lifecycleVersion != expectedVersion) {
            throw new PublicApiException(PublicApiErrorDescriptor.SAVED_SEARCH_VERSION_STALE, "Saved search version is stale");
        }
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getOwnerOperatorId() { return ownerOperatorId; }
    public String getName() { return name; }
    public SavedSearchVisibility getVisibility() { return visibility; }
    public String getFilterSchemaVersion() { return filterSchemaVersion; }
    public String getCanonicalFilterJson() { return canonicalFilterJson; }
    public ReviewQueueSort getSortCode() { return sortCode; }
    public String getVisibleColumnsJson() { return visibleColumnsJson; }
    public String getDefinitionDigest() { return definitionDigest; }
    public SavedSearchLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public long getLifecycleVersion() { return lifecycleVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDisabledAt() { return disabledAt; }
}
