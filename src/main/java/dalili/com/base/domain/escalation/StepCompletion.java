package dalili.com.base.domain.escalation;

import java.time.Instant;

public record StepCompletion(
        String stepId,
        String completedBy,
        Instant completedAt,
        String notes,
        boolean skipped,
        String skipReason
) {
}
