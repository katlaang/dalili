package dalili.com.dalili.infra.audit.sync;

import dalili.com.dalili.infra.audit.AuditVerificationResult;
import dalili.com.dalili.infra.audit.AuditVerificationService;
import dalili.com.dalili.infra.audit.health.AuditHealthStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditSyncScheduler {

    private final AuditSyncService syncService;
    private final AuditVerificationService verificationService;

    public AuditSyncScheduler(
            AuditSyncService syncService,
            AuditVerificationService verificationService
    ) {
        this.syncService = syncService;
        this.verificationService = verificationService;
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void attemptSync() {
        try {
            AuditVerificationResult result =
                    verificationService.verifyChain();

            if (!result.valid()) {
                AuditHealthStatus.markTampered();
                return;
            }

            // Record healthy verification
            AuditHealthStatus.markHealthy(result.eventCount());

            // Exercise sync logic safely
            syncService.preparePayload(null);

        } catch (Exception e) {
            // Retry later
        }
    }
}


