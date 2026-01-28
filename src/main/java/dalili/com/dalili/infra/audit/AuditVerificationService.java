package dalili.com.dalili.infra.audit;


// N.previousHash == hash(N-1) AND hash(N) == recomputedHash(N)


import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuditVerificationService {

    private final AuditEventRepository repository;

    public AuditVerificationService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public AuditVerificationResult verifyChain() {
        List<AuditEvent> events =
                repository.findAllByOrderByTimestampAsc();

        String expectedPreviousHash = null;

        for (AuditEvent event : events) {

            // 1️⃣ Verify chain linkage
            if (expectedPreviousHash != null &&
                    !expectedPreviousHash.equals(event.getPreviousHash())) {

                return AuditVerificationResult.tampered(
                        "Broken chain at event " + event.getId()
                );
            }

            // 2️⃣ Verify hash integrity
            String recomputedHash = recomputeHash(event);

            if (!recomputedHash.equals(event.getHash())) {
                return AuditVerificationResult.tampered(
                        "Hash mismatch at event " + event.getId()
                );
            }

            expectedPreviousHash = event.getHash();
        }

        return AuditVerificationResult.valid(events.size());
    }

    private String recomputeHash(AuditEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            String payload =
                    event.getTimestamp().toString() +
                            event.getEventType() +
                            event.getSessionId() +
                            event.getPhysicianId() +
                            (event.getPatientId() != null ? event.getPatientId() : "") +
                            event.getDeviceId() +
                            (event.getDetails() != null ? event.getDetails() : "") +
                            (event.getPreviousHash() != null ? event.getPreviousHash() : "");

            byte[] hashBytes =
                    digest.digest(payload.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hashBytes);

        } catch (Exception e) {
            throw new IllegalStateException("Audit verification failed", e);
        }
    }
}
