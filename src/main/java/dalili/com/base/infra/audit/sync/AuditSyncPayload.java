package dalili.com.base.infra.audit.sync;

import dalili.com.base.infra.audit.AuditEvent;

import java.util.List;

public record AuditSyncPayload(
        String lastKnownHash,
        List<AuditEvent> newEvents
) {
}

