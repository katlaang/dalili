package dalili.com.dalili.domain.session;

import dalili.com.dalili.domain.user.model.ActorType;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks session activity for inactivity timeout enforcement.
 * Updated on every authenticated request.
 */
@Entity
@Table(name = "active_sessions")
@Getter
public class ActiveSession {

    @Id
    private UUID sessionId;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActorType actorType;

    @Column(nullable = false)
    private Instant lastActivity;

    @Column(nullable = false)
    private Instant createdAt;

    protected ActiveSession() {
    }

    public ActiveSession(UUID sessionId, UUID userId, ActorType actorType) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.actorType = actorType;
        this.createdAt = Instant.now();
        this.lastActivity = Instant.now();
    }

    public void touch() {
        this.lastActivity = Instant.now();
    }
}
