package com.thaap.payments.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public CaptureLedgerResult recordCapture(UUID merchantId, UUID paymentIntentId, long amount, String currency) {
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capture amount must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capture currency is required");
        }
        List<LedgerEntry> existingEntries = ledgerEntryRepository.findByMerchantIdAndSourceTypeAndSourceId(merchantId,
                LedgerSourceType.PAYMENT_INTENT_CAPTURE, paymentIntentId);
        if (!existingEntries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Capture ledger entries already exist for PaymentIntent");
        }

        UUID transactionGroupId = UUID.randomUUID();
        List<LedgerEntry> entries = List.of(
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.PAYMENT_INTENT_CAPTURE,
                        paymentIntentId, LedgerAccount.CUSTOMER_RECEIVABLE, LedgerDirection.DEBIT, amount,
                        currency.toUpperCase()),
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.PAYMENT_INTENT_CAPTURE,
                        paymentIntentId, LedgerAccount.MERCHANT_PAYABLE, LedgerDirection.CREDIT, amount,
                        currency.toUpperCase()));
        requireBalanced(entries);
        List<LedgerEntry> savedEntries = ledgerEntryRepository.saveAll(entries);
        return new CaptureLedgerResult(transactionGroupId, savedEntries);
    }

    private void requireBalanced(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ledger transaction group cannot be empty");
        }
        String currency = entries.get(0).getCurrency();
        UUID merchantId = entries.get(0).getMerchantId();
        UUID transactionGroupId = entries.get(0).getTransactionGroupId();
        long debits = 0L;
        long credits = 0L;
        for (LedgerEntry entry : entries) {
            if (entry.getAmount() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ledger entry amount must be positive");
            }
            if (!entry.getCurrency().equals(currency)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ledger transaction group must use one currency");
            }
            if (!entry.getMerchantId().equals(merchantId)
                    || !entry.getTransactionGroupId().equals(transactionGroupId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ledger transaction group must share merchant and transaction group");
            }
            if (entry.getDirection() == LedgerDirection.DEBIT) {
                debits += entry.getAmount();
            } else if (entry.getDirection() == LedgerDirection.CREDIT) {
                credits += entry.getAmount();
            }
        }
        if (debits != credits) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ledger transaction group is not balanced");
        }
    }

    public record CaptureLedgerResult(UUID transactionGroupId, List<LedgerEntry> entries) {
    }
}
