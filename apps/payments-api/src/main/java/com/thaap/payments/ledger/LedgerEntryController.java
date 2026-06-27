package com.thaap.payments.ledger;

import com.thaap.payments.tenant.MerchantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/ledger/entries")
public class LedgerEntryController {

    static final int DEFAULT_LEDGER_ENTRY_LIMIT = 50;
    static final int MAX_LEDGER_ENTRY_LIMIT = 100;

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerEntryController(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @GetMapping
    public List<LedgerEntryResponse> listLedgerEntries(MerchantContext merchantContext,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LEDGER_ENTRY_LIMIT) int limit) {
        if (limit < 1 || limit > MAX_LEDGER_ENTRY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ledger entry limit must be between 1 and " + MAX_LEDGER_ENTRY_LIMIT);
        }
        return ledgerEntryRepository.findByMerchantIdOrderByCreatedAtDescIdDesc(merchantContext.merchantId(),
                        PageRequest.of(0, limit))
                .stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    public record LedgerEntryResponse(UUID id, UUID transactionGroupId, LedgerSourceType sourceType, UUID sourceId,
            UUID paymentIntentId, UUID refundId, LedgerAccount account, LedgerDirection direction, long amount,
            String currency, Instant createdAt) {
        static LedgerEntryResponse from(LedgerEntry ledgerEntry) {
            return new LedgerEntryResponse(ledgerEntry.getId(), ledgerEntry.getTransactionGroupId(),
                    ledgerEntry.getSourceType(), ledgerEntry.getSourceId(), ledgerEntry.getPaymentIntentId(),
                    ledgerEntry.getRefundId(), ledgerEntry.getAccount(), ledgerEntry.getDirection(),
                    ledgerEntry.getAmount(), ledgerEntry.getCurrency(), ledgerEntry.getCreatedAt());
        }
    }
}
