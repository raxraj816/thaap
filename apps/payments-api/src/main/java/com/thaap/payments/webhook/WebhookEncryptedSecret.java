package com.thaap.payments.webhook;

public record WebhookEncryptedSecret(String ciphertext, String nonce, String keyVersion) {
}
