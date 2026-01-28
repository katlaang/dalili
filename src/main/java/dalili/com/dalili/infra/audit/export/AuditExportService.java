package dalili.com.dalili.infra.audit.export;

import dalili.com.dalili.infra.audit.AuditEvent;
import dalili.com.dalili.infra.audit.AuditEventRepository;
import dalili.com.dalili.infra.audit.AuditVerificationResult;
import dalili.com.dalili.infra.audit.AuditVerificationService;
import dalili.com.dalili.interfaces.security.AuditGuard;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.util.List;

@Service
public class AuditExportService {

    private final AuditEventRepository repository;
    private final AuditVerificationService verificationService;
    private final AuditGuard auditGuard;

    public AuditExportService(
            AuditEventRepository repository,
            AuditVerificationService verificationService,
            AuditGuard auditGuard
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
        this.auditGuard = auditGuard;
    }

    public void exportToCsv(PrintWriter writer) {

        //  Policy enforcement
        auditGuard.assertSessionActive();
        auditGuard.assertAuditHealthy();
        auditGuard.assertCanExportAudit();

        //  Verify integrity before export
        AuditVerificationResult result =
                verificationService.verifyChain();

        if (!result.valid()) {
            throw new IllegalStateException(
                    "Cannot export audit log: " + result.message()
            );
        }

        // Write header
        writer.println(
                "timestamp,eventType,sessionId,physicianId,patientId," +
                        "deviceId,details,previousHash,hash"
        );

        //  Write rows in canonical order
        List<AuditEvent> events =
                repository.findAllByOrderByTimestampAsc();

        for (AuditEvent event : events) {
            writer.printf(
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                    event.getTimestamp(),
                    event.getEventType(),
                    event.getSessionId(),
                    event.getPhysicianId(),
                    event.getPatientId(),
                    event.getDeviceId(),
                    escape(event.getDetails()),
                    event.getPreviousHash(),
                    event.getHash()
            );
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
