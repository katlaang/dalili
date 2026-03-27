package dalili.com.base.application.controller;

import dalili.com.base.application.service.AppointmentService;
import dalili.com.base.application.service.PatientService;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.interfaces.security.AuditGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/frontdesk")
public class FrontDeskController {

    private final PatientService patientService;
    private final AppointmentService appointmentService;
    private final AuditGuard auditGuard;

    public FrontDeskController(
            PatientService patientService,
            AppointmentService appointmentService,
            AuditGuard auditGuard
    ) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
        this.auditGuard = auditGuard;
    }

    @GetMapping("/patient-lookup")
    public ResponseEntity<?> lookupPatientByMrnAndDob(
            @RequestParam String mrn,
            @RequestParam LocalDate dateOfBirth
    ) {
        try {
            auditGuard.assertSessionActive();
            Patient patient = patientService.findForFrontDeskLookup(mrn == null ? "" : mrn.trim(), dateOfBirth);

            boolean hasPendingAppointment = !appointmentService.getPatientPendingAppointments(patient.getId()).isEmpty();

            return ResponseEntity.ok(new FrontDeskPatientLookupResponse(
                    patient.getMrn(),
                    patient.getId(),
                    patient.getFullName(),
                    patient.getDateOfBirth(),
                    hasPendingAppointment
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record FrontDeskPatientLookupResponse(
            String patientId,
            UUID patientUuid,
            String fullName,
            LocalDate dateOfBirth,
            boolean hasPendingAppointment
    ) {
    }

    public record ErrorResponse(String error) {
    }
}
