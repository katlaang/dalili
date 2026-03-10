package dalili.com.base.domain.policy;

/**
 * Thresholds for each alert level.
 */
public record AlertThresholds(
        int level1Min,
        int level1Max,
        int level2Min,
        int level2Max,
        int level3Min
) {

    public static AlertThresholds defaults() {
        return new AlertThresholds(1, 2, 3, 4, 5);
    }
}
