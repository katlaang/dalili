package dalili.com.dalili.ambient.session;

import dalili.com.dalili.infra.audit.AuditService;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SessionLifecycleManager {

    private final SessionContext sessionContext;
    private final AuditService auditService;

    public SessionLifecycleManager(
            SessionContext sessionContext,
            AuditService auditService
    ) {
        this.sessionContext = sessionContext;
        this.auditService = auditService;
    }

    public UUID startSession(String physicianId) {
        UUID sessionId = UUID.randomUUID();

        sessionContext.bind(sessionId, physicianId);

        auditService.record(
                "SESSION_STARTED",
                null,
                "Physician logged in"
        );

        return sessionId;
    }

    public void endSession() {
        auditService.record(
                "SESSION_ENDED",
                null,
                "Physician logged out"
        );

        sessionContext.clear();
    }
}

