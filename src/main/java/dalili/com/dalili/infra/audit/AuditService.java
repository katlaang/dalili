package dalili.com.dalili.infra.audit;

import dalili.com.dalili.ambient.session.SessionContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final DeviceContext deviceContext;
    private final SessionContext sessionContext;

    public AuditService(
            AuditEventRepository repository,
            DeviceContext deviceContext,
            SessionContext sessionContext
    ) {
        this.repository = repository;
        this.deviceContext = deviceContext;
        this.sessionContext = sessionContext;
    }

    public void record(
            String eventType,
            UUID patientId,
            String details
    ) {
        AuditScope.enter();
        try {
            Instant timestamp = Instant.now();

            String previousHash = repository
                    .findTopByOrderByTimestampDesc()
                    .map(AuditEvent::getHash)
                    .orElse(null);

            String hash = AuditHashUtil.compute(
                    timestamp,
                    eventType,
                    sessionContext.sessionId(),
                    sessionContext.physicianId(),
                    patientId,
                    deviceContext.deviceId(),
                    details,
                    previousHash
            );

            AuditEvent event = new AuditEvent(
                    timestamp,
                    eventType,
                    sessionContext.sessionId(),
                    sessionContext.physicianId(),
                    patientId,
                    deviceContext.deviceId(),
                    details,
                    previousHash,
                    hash
            );

            repository.save(event);

        } finally {
            AuditScope.exit();
        }
    }
}