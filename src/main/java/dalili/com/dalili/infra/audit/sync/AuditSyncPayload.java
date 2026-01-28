package dalili.com.dalili.infra.audit.sync;

import dalili.com.dalili.infra.audit.AuditEvent;

import java.util.List;

public record AuditSyncPayload(
        String lastKnownHash,
        List<AuditEvent> newEvents
) {
}

