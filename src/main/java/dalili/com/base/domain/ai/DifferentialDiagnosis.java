package dalili.com.base.domain.ai;

import java.util.List;

public record DifferentialDiagnosis(
        String icd10,
        String name,
        ConfidenceLevel confidence,
        boolean ruleOut,
        String reasoning,
        List<String> suggestedTests
) {
}
