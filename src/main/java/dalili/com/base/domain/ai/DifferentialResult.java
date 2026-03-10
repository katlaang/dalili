package dalili.com.base.domain.ai;

import java.time.Instant;
import java.util.List;

public record DifferentialResult(
        boolean available,
        List<DifferentialDiagnosis> differentials,
        List<String> urgentConsiderations,
        List<String> additionalHistoryNeeded,
        String provider,
        String model,
        long latencyMs,
        Instant generatedAt,
        String errorMessage
) {
    public static DifferentialResult unavailable() {
        return new DifferentialResult(false, List.of(), List.of(), List.of(),
                null, null, 0, Instant.now(), "AI service unavailable");
    }

    public static DifferentialResult error(String message) {
        return new DifferentialResult(false, List.of(), List.of(), List.of(),
                null, null, 0, Instant.now(), message);
    }

    public List<DifferentialDiagnosis> getRuleOutConditions() {
        return differentials.stream().filter(DifferentialDiagnosis::ruleOut).toList();
    }

    public List<DifferentialDiagnosis> getLikelyConditions() {
        return differentials.stream().filter(d -> !d.ruleOut()).toList();
    }

    public List<String> getAllSuggestedTests() {
        return differentials.stream()
                .flatMap(d -> d.suggestedTests().stream())
                .distinct()
                .toList();
    }
}
