package dalili.com.dalili.infra.audit.sync;

import dalili.com.dalili.infra.audit.AuditEvent;
import dalili.com.dalili.infra.audit.AuditEventRepository;
import dalili.com.dalili.infra.audit.AuditVerificationResult;
import dalili.com.dalili.infra.audit.AuditVerificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditSyncService {

    private final AuditEventRepository repository;
    private final AuditVerificationService verificationService;

    public AuditSyncService(
            AuditEventRepository repository,
            AuditVerificationService verificationService
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
    }

    /**
     * Prepare payload for sending to a remote node
     */
    public AuditSyncPayload preparePayload(String remoteLastHash) {

        List<AuditEvent> all =
                repository.findAllByOrderByTimestampAsc();

        int startIndex = 0;

        if (remoteLastHash != null) {
            for (int i = 0; i < all.size(); i++) {
                if (remoteLastHash.equals(all.get(i).getHash())) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        return new AuditSyncPayload(
                lastLocalHash(),
                all.subList(startIndex, all.size())
        );
    }

    /**
     * Apply payload received from remote node
     */
    @Transactional
    public void applyPayload(AuditSyncPayload payload) {

        // Verify local chain first
        AuditVerificationResult localCheck =
                verificationService.verifyChain();

        if (!localCheck.valid()) {
            throw new IllegalStateException(
                    "Local audit chain is compromised"
            );
        }

        //  Verify continuity
        String localLastHash = lastLocalHash();

        if (localLastHash != null &&
                !localLastHash.equals(payload.lastKnownHash())) {

            throw new IllegalStateException(
                    "Audit sync rejected: hash mismatch"
            );
        }

        //  Append events exactly as received
        repository.saveAll(payload.newEvents());

        // Verify again after append
        AuditVerificationResult finalCheck =
                verificationService.verifyChain();

        if (!finalCheck.valid()) {
            throw new IllegalStateException(
                    "Audit chain corrupted during sync"
            );
        }
    }

    private String lastLocalHash() {
        return repository
                .findTopByOrderByTimestampDesc()
                .map(AuditEvent::getHash)
                .orElse(null);
    }
}

