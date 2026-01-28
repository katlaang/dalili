package dalili.com.dalili.infra.audit.anchoring;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Record of an audit chain anchor - proof that the chain existed in this state at this time.
 */
@Entity
@Table(name = "audit_anchors")
@Getter
public class AnchorRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private Instant anchorTime;

    @Column(nullable = false, updatable = false, length = 64)
    private String headHash;

    @Column(nullable = false, updatable = false)
    private long eventCount;

    @Column(nullable = true, updatable = false)
    private String externalProofId;

    protected AnchorRecord() {
    }

    public AnchorRecord(Instant anchorTime, String headHash, long eventCount, String externalProofId) {
        this.anchorTime = anchorTime;
        this.headHash = headHash;
        this.eventCount = eventCount;
        this.externalProofId = externalProofId;
    }
}
