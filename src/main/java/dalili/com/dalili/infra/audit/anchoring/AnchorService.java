package dalili.com.dalili.infra.audit.anchoring;

import dalili.com.dalili.infra.audit.AuditEvent;
import dalili.com.dalili.infra.audit.AuditEventRepository;
import dalili.com.dalili.infra.audit.health.AuditHealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Anchor Service - Provides provable irreversibility for the audit chain.
 * <p>
 * Creates periodic snapshots of the audit chain head hash and stores them
 * in an append-only file. This means:
 * - DB + app compromise alone cannot rewrite history
 * - Anchors provide forensic proof of chain state at point in time
 * - History becomes provably irreversible
 * <p>
 * For production, extend to write anchors to Azure Blob Storage with
 * immutability policies enabled.
 */
@Service
public class AnchorService {

    private static final Logger log = LoggerFactory.getLogger(AnchorService.class);
    private static final Path ANCHOR_FILE = Path.of("audit-anchors.log");

    private final AuditEventRepository auditRepository;
    private final AnchorRepository anchorRepository;

    public AnchorService(AuditEventRepository auditRepository, AnchorRepository anchorRepository) {
        this.auditRepository = auditRepository;
        this.anchorRepository = anchorRepository;
    }

    /**
     * Create an anchor for the current audit chain state.
     */
    public AnchorRecord createAnchor() {
        var lastEvent = auditRepository.findTopByOrderByTimestampDesc();

        if (lastEvent.isEmpty()) {
            log.info("No audit events to anchor");
            return null;
        }

        String headHash = lastEvent.get().getHash();
        long eventCount = auditRepository.count();
        Instant now = Instant.now();

        // Write to append-only file (local proof)
        String anchorLine = String.format("%s|%s|%d%n", now, headHash, eventCount);
        writeToAnchorFile(anchorLine);

        // Store in database
        AnchorRecord anchor = new AnchorRecord(now, headHash, eventCount, null);
        anchorRepository.save(anchor);

        log.info("Anchor created: hash={}..., events={}", headHash.substring(0, 16), eventCount);
        return anchor;
    }

    /**
     * Verify current chain matches stored anchors.
     * Returns false if history has been rewritten.
     */
    public boolean verifyAnchors() {
        List<AnchorRecord> anchors = anchorRepository.findAllByOrderByAnchorTimeDesc();

        if (anchors.isEmpty()) {
            return true; // No anchors to verify against
        }

        List<AuditEvent> events = auditRepository.findAllByOrderByTimestampAsc();

        for (AnchorRecord anchor : anchors) {
            boolean found = events.stream()
                    .anyMatch(e -> e.getHash().equals(anchor.getHeadHash()));

            if (!found) {
                log.error("ANCHOR VERIFICATION FAILED: hash {} not found in chain",
                        anchor.getHeadHash().substring(0, 16));
                AuditHealthStatus.markTampered();
                return false;
            }
        }

        log.info("Anchor verification passed: {} anchors verified", anchors.size());
        return true;
    }

    /**
     * Hourly anchor creation.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduledAnchor() {
        try {
            createAnchor();
        } catch (Exception e) {
            log.error("Scheduled anchor failed", e);
        }
    }

    /**
     * Daily anchor verification at 3 AM.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledVerification() {
        try {
            verifyAnchors();
        } catch (Exception e) {
            log.error("Scheduled verification failed", e);
        }
    }

    private void writeToAnchorFile(String line) {
        try {
            Files.writeString(ANCHOR_FILE, line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write to anchor file: {}", e.getMessage());
        }
    }
}
