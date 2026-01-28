package dalili.com.dalili.interfaces.security;

import dalili.com.dalili.ambient.session.SessionContext;
import dalili.com.dalili.infra.audit.health.AuditHealthStatus;
import org.springframework.stereotype.Component;

@Component
public class AuditGuard {

    private final SessionContext sessionContext;
    private final AuditAuthorizationService authorizationService;

    public AuditGuard(
            SessionContext sessionContext,
            AuditAuthorizationService authorizationService
    ) {
        this.sessionContext = sessionContext;
        this.authorizationService = authorizationService;
    }

    public void assertSessionActive() {
        if (!sessionContext.isActive()) {
            throw new AuditBypassException(
                    "Attempted data access outside active session"
            );
        }
    }

    public void assertAuditHealthy() {
        if (AuditHealthStatus.isTamperDetected()) {
            throw new AuditBypassException(
                    "Audit ledger integrity compromised; operation blocked"
            );
        }
    }

    public void assertCanExportAudit() {
        String physicianId = sessionContext.physicianId();

        if (!authorizationService.canExportAudit(physicianId)) {
            throw new AuditBypassException(
                    "Physician not authorized to export audit logs"
            );
        }
    }
}
