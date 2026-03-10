package dalili.com.base.domain.policy;

/**
 * EscalationScoring defines the point values and thresholds for tiered alerts.
 * <p>
 * Level 1 (1-2 pts): Passive panel - informational only
 * Level 2 (3-4 pts): Soft interrupt - requires acknowledgment
 * Level 3 (5+ pts): Hard interrupt - modal with protocol checklist
 */
public record EscalationScoring(
        PointValues points,
        AlertThresholds thresholds
) {

    public EscalationScoring {
        if (points == null) {
            points = PointValues.defaults();
        }
        if (thresholds == null) {
            thresholds = AlertThresholds.defaults();
        }
    }

    public static EscalationScoring defaults() {
        return new EscalationScoring(
                PointValues.defaults(),
                AlertThresholds.defaults()
        );
    }

    /**
     * Determine alert level based on total points.
     */
    public AlertLevel determineLevel(int totalPoints) {
        if (totalPoints >= thresholds.level3Min()) {
            return AlertLevel.LEVEL_3;
        }
        if (totalPoints >= thresholds.level2Min() && totalPoints <= thresholds.level2Max()) {
            return AlertLevel.LEVEL_2;
        }
        if (totalPoints >= thresholds.level1Min() && totalPoints <= thresholds.level1Max()) {
            return AlertLevel.LEVEL_1;
        }
        return AlertLevel.NONE;
    }
}

