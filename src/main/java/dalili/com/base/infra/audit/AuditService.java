package dalili.com.base.infra.audit;

import dalili.com.base.ambient.session.SessionContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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

            // Time integrity check
            Instant lastTimestamp = repository
                    .findTopByOrderByTimestampDesc()
                    .map(AuditEvent::getTimestamp)
                    .orElse(null);

            if (lastTimestamp != null && timestamp.isBefore(lastTimestamp)) {
                throw new ClockManipulationException(
                        "Audit rejected: current time " + timestamp +
                                " is before previous event " + lastTimestamp +
                                ". Possible clock manipulation."
                );
            }

            String previousHash = repository
                    .findTopByOrderByTimestampDesc()
                    .map(AuditEvent::getHash)
                    .orElse(null);

            UUID resolvedSessionId = sessionContext.sessionId() != null
                    ? sessionContext.sessionId()
                    : UUID.nameUUIDFromBytes(("ANON-KIOSK-" + deviceContext.deviceId()).getBytes(StandardCharsets.UTF_8));
            String resolvedActor = sessionContext.physicianId() != null
                    ? sessionContext.physicianId()
                    : "ANON_KIOSK";

            String hash = AuditHashUtil.compute(
                    timestamp,
                    eventType,
                    resolvedSessionId,
                    resolvedActor,
                    patientId,
                    deviceContext.deviceId(),
                    details,
                    previousHash
            );

            AuditEvent event = new AuditEvent(
                    timestamp,
                    eventType,
                    resolvedSessionId,
                    resolvedActor,
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
