package com.thaap.payments.velocity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class VelocityAssessment {

    private static final VelocityAssessment DISABLED = new VelocityAssessment(List.of(), List.of(), List.of());

    private final List<VelocitySignal> signals;
    private final List<VelocityObservationRecord> records;
    private final List<VelocityReservation> reservations;

    VelocityAssessment(List<VelocitySignal> signals, List<VelocityObservationRecord> records,
            List<VelocityReservation> reservations) {
        this.signals = List.copyOf(signals);
        this.records = List.copyOf(records);
        this.reservations = List.copyOf(reservations);
        if (this.signals.size() != this.records.size()) {
            throw new IllegalArgumentException("Velocity signals and durable records must reconcile");
        }
    }

    public static VelocityAssessment disabled() {
        return DISABLED;
    }

    public List<VelocitySignal> signals() {
        return signals;
    }

    List<VelocityReservation> reservations() {
        return reservations;
    }

    public boolean requiresReviewFloor() {
        return signals.stream().anyMatch(signal -> signal.availability() == VelocityAvailability.UNAVAILABLE
                || signal.thresholdMatched());
    }

    public boolean hasUnavailableSignal() {
        return signals.stream().anyMatch(signal -> signal.availability() == VelocityAvailability.UNAVAILABLE);
    }

    public boolean hasThresholdMatch() {
        return signals.stream().anyMatch(VelocitySignal::thresholdMatched);
    }

    public void attachFraudDecision(UUID fraudDecisionId) {
        Objects.requireNonNull(fraudDecisionId, "fraudDecisionId");
        records.forEach(record -> record.attachFraudDecision(fraudDecisionId));
    }
}
