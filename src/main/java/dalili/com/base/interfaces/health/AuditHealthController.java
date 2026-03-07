package dalili.com.base.interfaces.health;

import dalili.com.base.infra.audit.health.AuditHealthStatus;
import dalili.com.base.interfaces.audit.AuditHealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditHealthController {

    @GetMapping("/audit/health")
    public AuditHealthResponse health() {
        return new AuditHealthResponse(
                AuditHealthStatus.isTamperDetected(),
                AuditHealthStatus.lastSuccessfulVerification(),
                AuditHealthStatus.lastVerifiedEventCount()
        );
    }
}

