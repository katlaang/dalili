package dalili.com.dalili.infra.audit;

public record AuditVerificationResult(
        boolean valid,
        String message,
        int eventCount
) {
    public static AuditVerificationResult valid(int count) {
        return new AuditVerificationResult(
                true,
                "Audit chain intact",
                count
        );
    }

    public static AuditVerificationResult tampered(String message) {
        return new AuditVerificationResult(
                false,
                message,
                -1
        );
    }
}
