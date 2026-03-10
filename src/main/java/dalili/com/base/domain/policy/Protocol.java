package dalili.com.base.domain.policy;

import java.util.List;

/**
 * Protocol defines an emergency checklist with trackable, audited steps.
 */
public record Protocol(
        String id,
        String name,
        ClusterUrgency urgency,
        boolean timeCritical,
        boolean regionSpecific,
        String ageGroup,
        List<ProtocolStep> steps
) {
    public Protocol {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Protocol ID required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Protocol name required");
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("Steps required");
        steps = List.copyOf(steps);
    }

    public List<ProtocolStep> getRequiredSteps() {
        return steps.stream().filter(ProtocolStep::required).toList();
    }

    public List<ProtocolStep> getTimeCriticalSteps() {
        return steps.stream().filter(s -> s.timeLimitMinutes() != null).toList();
    }

    public boolean isApplicableForAge(int ageYears) {
        if (ageGroup == null) return true;
        return switch (ageGroup.toLowerCase()) {
            case "infant" -> ageYears < 1;
            case "pediatric" -> ageYears >= 1 && ageYears < 14;
            case "adult" -> ageYears >= 14;
            default -> true;
        };
    }
}
