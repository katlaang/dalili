package dalili.com.base.domain.ai;

public enum ConfidenceLevel {
    HIGH("High", ">70%"),
    MODERATE("Moderate", "40-70%"),
    LOW("Low", "<40%");

    private final String label;
    private final String range;

    ConfidenceLevel(String label, String range) {
        this.label = label;
        this.range = range;
    }

    public String getLabel() {
        return label;
    }

    public String getRange() {
        return range;
    }
}
