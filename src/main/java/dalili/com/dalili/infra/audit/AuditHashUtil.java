package dalili.com.dalili.infra.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class AuditHashUtil {

    private AuditHashUtil() {
        // utility class — no instances
    }

    public static String compute(
            Instant timestamp,
            String eventType,
            UUID sessionId,
            String physicianId,
            UUID patientId,
            String deviceId,
            String details,
            String previousHash
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            String payload =
                    timestamp.toString() +
                            eventType +
                            sessionId +
                            physicianId +
                            (patientId != null ? patientId.toString() : "") +
                            deviceId +
                            (details != null ? details : "") +
                            (previousHash != null ? previousHash : "");

            byte[] hashBytes =
                    digest.digest(payload.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hashBytes);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to compute audit hash",
                    e
            );
        }
    }
}
