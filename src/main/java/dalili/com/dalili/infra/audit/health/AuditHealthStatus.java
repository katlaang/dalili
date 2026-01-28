package dalili.com.dalili.infra.audit.health;

import lombok.Getter;

import java.time.Instant;

public final class AuditHealthStatus {

    private static volatile Instant lastSuccessfulVerification;
    @Getter
    private static volatile boolean tamperDetected = false;
    private static volatile int lastVerifiedEventCount = 0;

    private AuditHealthStatus() {}

    public static void markHealthy(int eventCount) {
        lastSuccessfulVerification = Instant.now();
        lastVerifiedEventCount = eventCount;
        tamperDetected = false;
    }

    public static void markTampered() {
        tamperDetected = true;
    }

    public static Instant lastSuccessfulVerification() {
        return lastSuccessfulVerification;
    }

    public static int lastVerifiedEventCount() {
        return lastVerifiedEventCount;
    }
}

