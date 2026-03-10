package dalili.com.base.domain.policy;

import java.util.List;

/**
 * A single action in an emergency protocol.
 */
public record ProtocolStep(
        String id,
        String action,
        boolean required,
        boolean conditional,
        Integer timeLimitMinutes,
        List<String> contraindications,
        String note,
        String ageGroup
) {
    public ProtocolStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Step ID required");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("Action required");
        contraindications = contraindications != null ? List.copyOf(contraindications) : List.of();
    }

    public boolean hasTimeLimit() {
        return timeLimitMinutes != null && timeLimitMinutes > 0;
    }

    public boolean hasContraindications() {
        return !contraindications.isEmpty();
    }

    public boolean hasNote() {
        return note != null && !note.isBlank();
    }
}
