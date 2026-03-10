package dalili.com.base.domain.policy;

/**
 * Point values assigned to different findings.
 */
public record PointValues(
        int criticalVital,
        int highRiskSymptom,
        int moderateConcern,
        int criticalPhrase,
        int highPhrase,
        int symptomClusterEmergency,
        int symptomClusterUrgent
) {

    public static PointValues defaults() {
        return new PointValues(3, 2, 1, 3, 2, 3, 2);
    }
}
