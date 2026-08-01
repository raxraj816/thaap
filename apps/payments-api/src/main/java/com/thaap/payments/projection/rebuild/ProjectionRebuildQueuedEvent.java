package com.thaap.payments.projection.rebuild;

import java.util.UUID;

public record ProjectionRebuildQueuedEvent(UUID rebuildId) {
}
