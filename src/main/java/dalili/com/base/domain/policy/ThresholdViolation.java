package dalili.com.base.domain.policy;

public record ThresholdViolation(String vitalSign, String direction, double threshold, double actualValue) {
    public String describe() {
        return String.format(
                "%s is %s threshold: actual %.1f, threshold %.1f",
                vitalSign, direction, actualValue, threshold
        );
    }
}
