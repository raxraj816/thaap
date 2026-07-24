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
        requirePositiveAmountAndCurrency(amount, currency, "Capture");
        List<LedgerEntry> existingEntries = ledgerEntryRepository.findByMerchantIdAndSourceTypeAndSourceId(merchantId,
                LedgerSourceType.PAYMENT_INTENT_CAPTURE, paymentIntentId);
        if (!existingEntries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Capture ledger entries already exist for PaymentIntent");
        }

        UUID transactionGroupId = UUID.randomUUID();
        List<LedgerEntry> entries = List.of(
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.PAYMENT_INTENT_CAPTURE,
                        paymentIntentId, paymentIntentId, null, LedgerAccount.CUSTOMER_RECEIVABLE,
                        LedgerDirection.DEBIT, amount, currency.toUpperCase()),
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.PAYMENT_INTENT_CAPTURE,
                        paymentIntentId, paymentIntentId, null, LedgerAccount.MERCHANT_PAYABLE,
                        LedgerDirection.CREDIT, amount, currency.toUpperCase()));
        requireBalanced(entries);
        List<LedgerEntry> savedEntries = ledgerEntryRepository.saveAll(entries);
        return new CaptureLedgerResult(transactionGroupId, savedEntries);
    }

    public RefundLedgerResult recordRefund(UUID merchantId, UUID paymentIntentId, UUID refundId, long amount,
            String currency) {
        requirePositiveAmountAndCurrency(amount, currency, "Refund");
        List<LedgerEntry> existingRefundEntries = ledgerEntryRepository.findByMerchantIdAndSourceTypeAndSourceId(
                merchantId, LedgerSourceType.REFUND, refundId);
        if (!existingRefundEntries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Refund ledger entries already exist for Refund");
        }

        List<LedgerEntry> captureEntries = ledgerEntryRepository.findByMerchantIdAndPaymentIntentIdAndSourceType(
                merchantId, paymentIntentId, LedgerSourceType.PAYMENT_INTENT_CAPTURE);
        UUID captureTransactionGroupId = requireValidCaptureLedger(captureEntries, amount, currency);

        UUID refundTransactionGroupId = UUID.randomUUID();
        List<LedgerEntry> refundEntries = List.of(
                new LedgerEntry(merchantId, refundTransactionGroupId, LedgerSourceType.REFUND, refundId,
                        paymentIntentId, refundId, LedgerAccount.MERCHANT_PAYABLE, LedgerDirection.DEBIT, amount,
                        currency.toUpperCase()),
                new LedgerEntry(merchantId, refundTransactionGroupId, LedgerSourceType.REFUND, refundId,
                        paymentIntentId, refundId, LedgerAccount.CUSTOMER_RECEIVABLE, LedgerDirection.CREDIT, amount,
                        currency.toUpperCase()));
        requireBalanced(refundEntries);
        List<LedgerEntry> savedEntries = ledgerEntryRepository.saveAll(refundEntries);
        return new RefundLedgerResult(captureTransactionGroupId, refundTransactionGroupId, savedEntries);
    }

    public ChargebackLedgerResult recordChargeback(UUID merchantId, UUID paymentIntentId, UUID chargebackId,
            long capturedAmount, long allocatedAmount, String currency) {
        requirePositiveAmountAndCurrency(allocatedAmount, currency, "Chargeback");
        List<LedgerEntry> existingEntries = ledgerEntryRepository.findByMerchantIdAndSourceTypeAndSourceId(
                merchantId, LedgerSourceType.CHARGEBACK, chargebackId);
        if (!existingEntries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Chargeback ledger entries already exist for Chargeback");
        }
        List<LedgerEntry> captureEntries = ledgerEntryRepository.findByMerchantIdAndPaymentIntentIdAndSourceType(
                merchantId, paymentIntentId, LedgerSourceType.PAYMENT_INTENT_CAPTURE);
        UUID captureTransactionGroupId = requireValidCaptureLedger(captureEntries, capturedAmount, currency);
        UUID transactionGroupId = UUID.randomUUID();
        List<LedgerEntry> entries = List.of(
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.CHARGEBACK, chargebackId,
                        paymentIntentId, null, chargebackId, LedgerAccount.MERCHANT_PAYABLE,
                        LedgerDirection.DEBIT, allocatedAmount, currency.toUpperCase()),
                new LedgerEntry(merchantId, transactionGroupId, LedgerSourceType.CHARGEBACK, chargebackId,
                        paymentIntentId, null, chargebackId, LedgerAccount.CUSTOMER_RECEIVABLE,
                        LedgerDirection.CREDIT, allocatedAmount, currency.toUpperCase()));
        requireBalanced(entries);
        return new ChargebackLedgerResult(captureTransactionGroupId, transactionGroupId,
                ledgerEntryRepository.saveAll(entries));
    }

    private void requirePositiveAmountAndCurrency(long amount, String currency, String operation) {
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, operation + " amount must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, operation + " currency is required");
        }
    }

    private UUID requireValidCaptureLedger(List<LedgerEntry> entries, long amount, String currency) {
        if (entries.size() != 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent does not have complete capture ledger evidence");
        }
        requireBalanced(entries);
        UUID transactionGroupId = entries.get(0).getTransactionGroupId();
        String normalizedCurrency = currency.toUpperCase();
        boolean customerReceivableDebit = false;
        boolean merchantPayableCredit = false;
        for (LedgerEntry entry : entries) {
            if (entry.getAmount() != amount || !entry.getCurrency().equals(normalizedCurrency)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Capture ledger amount or currency does not match Refund");
            }
            if (entry.getAccount() == LedgerAccount.CUSTOMER_RECEIVABLE
                    && entry.getDirection() == LedgerDirection.DEBIT) {
                customerReceivableDebit = true;
            }
            if (entry.getAccount() == LedgerAccount.MERCHANT_PAYABLE
                    && entry.getDirection() == LedgerDirection.CREDIT) {
                merchantPayableCredit = true;
            }
        }
        if (!customerReceivableDebit || !merchantPayableCredit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PaymentIntent capture ledger postings are incomplete");
        }
        return transactionGroupId;
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

    public record RefundLedgerResult(UUID captureTransactionGroupId, UUID transactionGroupId,
            List<LedgerEntry> entries) {
    }

    public record ChargebackLedgerResult(UUID captureTransactionGroupId, UUID transactionGroupId,
            List<LedgerEntry> entries) {
    }
}
