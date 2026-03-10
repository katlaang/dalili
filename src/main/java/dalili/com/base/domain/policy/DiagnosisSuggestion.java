package dalili.com.base.domain.policy;

public record DiagnosisSuggestion(String code, String name, boolean ruleOut, double confidence) {
    public DiagnosisSuggestion {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Diagnosis name required");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be 0-1");
        }
    }

    public String getWeightLabel() {
        if (confidence >= 0.8) return "High";
        if (confidence >= 0.5) return "Moderate";
        return "Low";
    }
}
