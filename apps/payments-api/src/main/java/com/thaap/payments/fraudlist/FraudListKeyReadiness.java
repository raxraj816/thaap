package com.thaap.payments.fraudlist;

import com.thaap.payments.security.SensitiveValueHasher;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FraudListKeyReadiness implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SensitiveValueHasher hasher;

    public FraudListKeyReadiness(JdbcTemplate jdbcTemplate, SensitiveValueHasher hasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.hasher = hasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyActiveEntryKeyInventory();
    }

    public void verifyActiveEntryKeyInventory() {
        List<String> keyVersions = jdbcTemplate.queryForList("""
                SELECT DISTINCT entry.hmac_key_version
                  FROM fraud_list_entries entry
                 WHERE entry.hmac_key_version IS NOT NULL
                   AND entry.effective_from <= CURRENT_TIMESTAMP
                   AND (entry.effective_until IS NULL OR entry.effective_until > CURRENT_TIMESTAMP)
                   AND NOT EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events entry_event
                        WHERE entry_event.merchant_id = entry.merchant_id
                          AND entry_event.fraud_list_entry_id = entry.id
                          AND entry_event.event_type = 'ENTRY_DEACTIVATED'
                          AND entry_event.occurred_at <= CURRENT_TIMESTAMP)
                   AND NOT EXISTS (
                       SELECT 1 FROM fraud_list_lifecycle_events version_event
                        WHERE version_event.merchant_id = entry.merchant_id
                          AND version_event.fraud_list_version_id = entry.fraud_list_version_id
                          AND version_event.event_type = 'VERSION_DEACTIVATED'
                          AND version_event.occurred_at <= CURRENT_TIMESTAMP)
                """, String.class);
        List<String> unavailable = keyVersions.stream().filter(version -> !hasher.canHashWith(version)).toList();
        if (!unavailable.isEmpty()) {
            throw new IllegalStateException(
                    "Active fraud-list entries require unavailable HMAC key versions: " + unavailable);
        }
    }
}
