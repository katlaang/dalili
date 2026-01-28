package dalili.com.dalili.interfaces.audit;

import java.time.Instant;

public record AuditHealthResponse(
        boolean tamperDetected,
        Instant lastSuccessfulVerification,
        int verifiedEventCount
) {
}

