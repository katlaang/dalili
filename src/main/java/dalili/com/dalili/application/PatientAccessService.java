package dalili.com.dalili.application;

import dalili.com.dalili.domain.Patient;
import dalili.com.dalili.infra.audit.AuditService;
import dalili.com.dalili.infra.persistence.PatientRepository;
import dalili.com.dalili.interfaces.security.AuditGuard;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PatientAccessService {

    private final PatientRepository repository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;

    public PatientAccessService(
            PatientRepository repository,
            AuditService auditService,
            AuditGuard auditGuard
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
    }

    public Patient openPatientFile(UUID patientId) {

        //  HARD GUARD — cannot be bypassed
        auditGuard.assertSessionActive();

        Patient patient = repository.findById(patientId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Patient not found"));

        auditService.record(
                "PATIENT_FILE_OPENED",
                patientId,
                "Patient record opened for review"
        );

        return patient;
    }
}