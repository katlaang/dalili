package dalili.com.dalili.domain.session.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks kiosk session usage to enforce single-use tokens.
 * Once a kiosk session is used (patient gets queue number), it cannot be reused.
 */
@Entity
@Table(name = "kiosk_sessions")
@Getter
public class KioskSession {

    @Id
    private UUID sessionId;

    @Column(nullable = false)
    private UUID kioskUserId;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean used;

    @Column
    private Instant usedAt;

    protected KioskSession() {
    }

    public KioskSession(UUID sessionId, UUID kioskUserId, UUID patientId) {
        this.sessionId = sessionId;
        this.kioskUserId = kioskUserId;
        this.patientId = patientId;
        this.createdAt = Instant.now();
        this.used = false;
        this.usedAt = null;
    }

    public void markUsed() {
        this.used = true;
        this.usedAt = Instant.now();
    }
}
