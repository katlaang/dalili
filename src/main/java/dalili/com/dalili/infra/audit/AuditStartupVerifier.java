package dalili.com.dalili.infra.audit;

import dalili.com.dalili.infra.audit.anchoring.AnchorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuditStartupVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditStartupVerifier.class);

    private final AuditVerificationService verificationService;
    private final AnchorService anchorService;

    public AuditStartupVerifier(
            AuditVerificationService verificationService,
            AnchorService anchorService
    ) {
        this.verificationService = verificationService;
        this.anchorService = anchorService;
    }

    @Override
    public void run(ApplicationArguments args) {

        // 1. Verify hash chain integrity
        log.info("Verifying audit hash chain integrity...");
        AuditVerificationResult chainResult = verificationService.verifyChain();

        if (!chainResult.valid()) {
            throw new IllegalStateException(
                    "Startup aborted: audit ledger integrity compromised. " +
                            chainResult.message()
            );
        }
        log.info("Hash chain verified: {} events", chainResult.eventCount());

        // 2. Verify chain matches historical anchors
        log.info("Verifying audit chain against historical anchors...");
        boolean anchorsValid = anchorService.verifyAnchors();

        if (!anchorsValid) {
            throw new IllegalStateException(
                    "Startup aborted: audit chain does not match historical anchors. " +
                            "Possible history rewrite detected."
            );
        }
        log.info("Anchor verification complete");
    }
}
