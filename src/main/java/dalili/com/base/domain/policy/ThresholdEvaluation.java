package dalili.com.base.domain.policy;

import java.util.List;

public record ThresholdEvaluation(AcuityLevel acuityLevel, List<ThresholdViolation> violations) {
}
