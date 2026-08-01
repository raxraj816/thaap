package com.thaap.payments.eventpublication;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "thaap.event-publication.enabled", havingValue = "true")
public class EventPublicationPublisher {

    private final EventPublicationStore store;
    private final KafkaDomainEventSender sender;
    private final EventPublicationMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public EventPublicationPublisher(EventPublicationStore store,
            KafkaDomainEventSender sender, EventPublicationMetrics metrics,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.sender = sender;
        this.metrics = metrics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<PublicationClaim> claimDue(String workerId, int limit) {
        List<PublicationClaim> claims = transaction.execute(status ->
                store.claimDue(clock.instant(), workerId, limit));
        if (claims == null) {
            throw new IllegalStateException("Publication claim transaction returned no result");
        }
        metrics.claimed(claims.size());
        for (PublicationClaim claim : claims) {
            if (claim.leaseRecovered()) {
                metrics.outcome(PublicationOutcome.LEASE_RECOVERED);
            }
        }
        return claims;
    }

    public PublicationFinalization publish(PublicationClaim claim) {
        PublicationSendResult sendResult = sender.send(claim);
        PublicationFinalization finalization = transaction.execute(status ->
                store.finalizeClaim(claim, sendResult, clock.instant()));
        if (finalization == null) {
            throw new IllegalStateException(
                    "Publication finalization transaction returned no result");
        }
        if (finalization == PublicationFinalization.APPLIED) {
            metrics.outcome(sendResult.outcome());
        } else {
            metrics.finalizationRejected(finalization);
        }
        return finalization;
    }

    public PublicationHealthSnapshot refreshBacklogMetrics() {
        PublicationHealthSnapshot snapshot = store.healthSnapshot();
        metrics.observeBacklog(snapshot);
        return snapshot;
    }
}
