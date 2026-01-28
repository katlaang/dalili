package dalili.com.dalili.infra.audit;

import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.util.List;

@Service
public class AuditExportService {

    private final AuditEventRepository repository;
    private final AuditVerificationService verificationService;

    public AuditExportService(
            AuditEventRepository repository,
            AuditVerificationService verificationService
    ) {
        this.repository = repository;
        this.verificationService = verificationService;
    }

    public void exportToCsv(PrintWriter writer) {

        // Verify integrity before export
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

        // Write rows in canonical order
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

