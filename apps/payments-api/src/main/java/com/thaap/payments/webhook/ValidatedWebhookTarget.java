package com.thaap.payments.webhook;

import java.net.InetAddress;
import java.net.URI;

public record ValidatedWebhookTarget(URI uri, InetAddress address, int port) {
}
