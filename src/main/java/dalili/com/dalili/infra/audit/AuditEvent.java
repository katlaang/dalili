package dalili.com.dalili.infra.audit;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "audit_events",
        indexes = {
                @Index(name = "idx_audit_session", columnList = "sessionId"),
                @Index(name = "idx_audit_patient", columnList = "patientId"),
                @Index(name = "idx_audit_physician", columnList = "physicianId"),
                @Index(name = "idx_audit_timestamp", columnList = "timestamp")
        }
)
@Getter
public class AuditEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private String physicianId;

    @Column(nullable = true, updatable = false)
    private UUID patientId;

    @Column(nullable = false, updatable = false)
    private String deviceId;

    @Column(nullable = true, length = 2000, updatable = false)
    private String details;

    // 🔐 TAMPER-EVIDENT FIELDS
    @Column(nullable = false, updatable = false, length = 64)
    private String hash;

    @Column(nullable = true, updatable = false, length = 64)
    private String previousHash;

    protected AuditEvent() {
        // JPA only
    }

    public AuditEvent(
            Instant timestamp,
            String eventType,
            UUID sessionId,
            String physicianId,
            UUID patientId,
            String deviceId,
            String details,
            String previousHash,
            String hash
    ) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.sessionId = sessionId;
        this.physicianId = physicianId;
        this.patientId = patientId;
        this.deviceId = deviceId;
        this.details = details;
        this.previousHash = previousHash;
        this.hash = hash;
    }


}