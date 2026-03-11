package dalili.com.base.application.controller;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.AppointmentService;
import dalili.com.base.application.service.FacilityWorkflowConfigService;
import dalili.com.base.application.service.PatientDataAccessService;
import dalili.com.base.domain.appointment.model.Appointment;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Patient-facing appointment check-in endpoints.
 */
@RestController
@RequestMapping("/api/patient/appointments")
public class PatientAppointmentController {

    private static final Logger log = LoggerFactory.getLogger(PatientAppointmentController.class);

    private final AppointmentService appointmentService;
    private final PatientDataAccessService patientDataAccessService;
    private final FacilityWorkflowConfigService facilityWorkflowConfigService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public PatientAppointmentController(
            AppointmentService appointmentService,
            PatientDataAccessService patientDataAccessService,
            FacilityWorkflowConfigService facilityWorkflowConfigService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.appointmentService = appointmentService;
        this.patientDataAccessService = patientDataAccessService;
        this.facilityWorkflowConfigService = facilityWorkflowConfigService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    private static String formatInstant(Instant value) {
        return value != null ? value.toString() : null;
    }

    private AppointmentView toView(Appointment appointment) {
        String facilityCode = appointment.getFacilityCode();
        String facilityName = appointment.getFacilityName();
        if ((facilityCode == null || facilityCode.isBlank()) || (facilityName == null || facilityName.isBlank())) {
            var config = facilityWorkflowConfigService.getCurrentConfig();
            if (facilityCode == null || facilityCode.isBlank()) {
                facilityCode = config.getFacilityCode();
            }
            if (facilityName == null || facilityName.isBlank()) {
                facilityName = config.getFacilityName();
            }
        }

        return new AppointmentView(
                appointment.getId(),
                appointment.getAppointmentNumber(),
                appointment.getStatus() != null ? appointment.getStatus().name() : null,
                formatInstant(appointment.getScheduledAt()),
                formatInstant(appointment.getCheckInWindowOpensAt()),
                formatInstant(appointment.getCheckInWindowClosesAt()),
                appointment.canCheckInAt(Instant.now()),
                appointment.getKioskAccessCode(),
                appointment.getKioskQrToken(),
                appointment.getClinicianName(),
                appointment.getClinicianEmployeeId(),
                appointment.getDepartmentName(),
                facilityCode,
                facilityName,
                appointment.getReason(),
                formatInstant(appointment.getCheckedInAt()),
                appointment.getQueueTicketId(),
                appointment.getDeactivationReason()
        );
    }

    @GetMapping("/pending")
    public ResponseEntity<?> getPendingAppointments() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            List<AppointmentView> pending = appointmentService.getPatientPendingAppointments(patientId).stream()
                    .map(this::toView)
                    .toList();
            return ResponseEntity.ok(pending);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getAppointmentHistory() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            List<AppointmentView> history = appointmentService.getPatientAppointmentHistory(patientId).stream()
                    .map(this::toView)
                    .toList();
            return ResponseEntity.ok(history);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{appointmentId}/checkin")
    public ResponseEntity<?> checkInAppointment(
            @PathVariable UUID appointmentId,
            @RequestBody(required = false) AppointmentCheckInRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();

            if (request != null && Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(patientId);
            }

            AppointmentService.AppointmentCheckInResult result = appointmentService.checkInAppointment(
                    patientId,
                    appointmentId,
                    request != null ? request.complaint() : null
            );
            log.info("Appointment checked in via patient portal appointmentId={} patientId={}",
                    appointmentId, patientId);

            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    toView(result.appointment()),
                    QueueTicketView.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    private UUID requirePatientSession() {
        UUID patientId = sessionContext.patientId();
        if (patientId == null) {
            throw new IllegalStateException("No patient linked to current session");
        }
        return patientId;
    }

    public record AppointmentCheckInRequest(
            String complaint,
            Boolean consentForDataAccess
    ) {
    }

    public record AppointmentCheckInResponse(
            AppointmentView appointment,
            QueueTicketView queueTicket
    ) {
    }

    public record AppointmentView(
            UUID id,
            String appointmentNumber,
            String status,
            String scheduledAt,
            String checkInWindowOpensAt,
            String checkInWindowClosesAt,
            boolean checkInEligibleNow,
            String kioskAccessCode,
            String kioskQrToken,
            String clinicianName,
            String clinicianEmployeeId,
            String departmentName,
            String facilityCode,
            String facilityName,
            String reason,
            String checkedInAt,
            UUID queueTicketId,
            String deactivationReason
    ) {
    }

    public record QueueTicketView(
            UUID id,
            String ticketNumber,
            String category,
            String status,
            String triageLevel,
            int targetWaitMinutes,
            long waitTimeMinutes,
            String createdAt
    ) {
        static QueueTicketView from(QueueTicket ticket) {
            return new QueueTicketView(
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getCategory() != null ? ticket.getCategory().name() : null,
                    ticket.getStatus() != null ? ticket.getStatus().name() : null,
                    ticket.getTriageLevel() != null ? ticket.getTriageLevel().name() : null,
                    ticket.getTargetWaitMinutes(),
                    ticket.getWaitTimeMinutes(),
                    formatInstant(ticket.getCreatedAt())
            );
        }
    }

    public record ErrorResponse(String error) {
    }
}
