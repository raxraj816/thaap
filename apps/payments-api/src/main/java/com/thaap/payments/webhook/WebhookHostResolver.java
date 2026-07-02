package com.thaap.payments.webhook;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookHostResolver {

    private static final int MAXIMUM_RESOLUTION_TIMEOUT_MILLIS = 60_000;

    private final int timeoutMillis;
    private final ExecutorService executor;
    private final HostLookup hostLookup;

    @Autowired
    public WebhookHostResolver(
            @Value("${thaap.webhook.target-resolution-timeout:2s}") Duration timeout,
            @Value("${thaap.webhook.resolver-threads:4}") int resolverThreads) {
        this(timeout, resolverThreads, InetAddress::getAllByName);
    }

    WebhookHostResolver(Duration timeout, int resolverThreads, HostLookup hostLookup) {
        this.timeoutMillis = WebhookDurationValidator.checkedWholeMilliseconds(
                timeout, "target resolution timeout", MAXIMUM_RESOLUTION_TIMEOUT_MILLIS);
        if (resolverThreads < 1 || resolverThreads > 32) {
            throw new IllegalArgumentException("Webhook resolver threads must be between 1 and 32");
        }
        this.hostLookup = hostLookup;
        this.executor = new ThreadPoolExecutor(
                resolverThreads,
                resolverThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(resolverThreads),
                Thread.ofPlatform().daemon().name("thaap-webhook-dns-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public InetAddress[] resolve(String host) {
        Future<InetAddress[]> resolution;
        try {
            resolution = executor.submit(() -> hostLookup.resolve(host));
        } catch (RejectedExecutionException exception) {
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname resolver capacity is exhausted",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR, exception);
        }
        try {
            return resolution.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            resolution.cancel(true);
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname resolution exceeded its deadline",
                    WebhookDeliveryErrorCode.CONNECT_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            resolution.cancel(true);
            Thread.currentThread().interrupt();
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname resolution was interrupted",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof UnknownHostException) {
                throw new WebhookTargetResolutionException(
                        "Webhook target hostname is currently unresolved",
                        WebhookDeliveryErrorCode.TRANSPORT_ERROR, cause);
            }
            throw new WebhookTargetResolutionException(
                    "Webhook target hostname resolution failed",
                    WebhookDeliveryErrorCode.TRANSPORT_ERROR, cause);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface HostLookup {
        InetAddress[] resolve(String host) throws Exception;
    }
}
