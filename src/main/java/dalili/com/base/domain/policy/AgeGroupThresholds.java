package dalili.com.base.domain.policy;

import java.util.List;

/**
 * Threshold tiers for a specific age group.
 */
public record AgeGroupThresholds(
        ThresholdTier emergency,
        ThresholdTier urgent,
        ThresholdTier standard
) {

    public ThresholdEvaluation evaluate(VitalSigns vitals) {
        if (emergency != null && emergency.matches(vitals)) {
            return new ThresholdEvaluation(AcuityLevel.EMERGENCY, emergency.getMatchedThresholds(vitals));
        }
        if (urgent != null && urgent.matches(vitals)) {
            return new ThresholdEvaluation(AcuityLevel.URGENT, urgent.getMatchedThresholds(vitals));
        }
        if (standard != null && standard.matches(vitals)) {
            return new ThresholdEvaluation(AcuityLevel.STANDARD, standard.getMatchedThresholds(vitals));
        }
        return new ThresholdEvaluation(AcuityLevel.NON_URGENT, List.of());
    }
}
