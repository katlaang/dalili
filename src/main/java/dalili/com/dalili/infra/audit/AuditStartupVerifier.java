package dalili.com.dalili.infra.audit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuditStartupVerifier implements ApplicationRunner {

    private final AuditVerificationService verificationService;

    public AuditStartupVerifier(AuditVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Override
    public void run(ApplicationArguments args) {

        AuditVerificationResult result =
                verificationService.verifyChain();

        if (!result.valid()) {
            throw new IllegalStateException(
                    "Startup aborted: audit ledger integrity compromised. " +
                            result.message()
            );
        }
    }
}
