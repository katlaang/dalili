package dalili.com.base.application.controller;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.AppointmentService;
import dalili.com.base.application.service.FacilityWorkflowConfigService;
import dalili.com.base.application.service.PatientDataAccessService;
import dalili.com.base.application.service.PatientService;
import dalili.com.base.domain.appointment.model.Appointment;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff-facing appointment scheduling and check-in endpoints.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private static final Logger log = LoggerFactory.getLogger(AppointmentController.class);

    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final PatientDataAccessService patientDataAccessService;
    private final FacilityWorkflowConfigService facilityWorkflowConfigService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public AppointmentController(
            AppointmentService appointmentService,
            PatientService patientService,
            PatientDataAccessService patientDataAccessService,
            FacilityWorkflowConfigService facilityWorkflowConfigService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.appointmentService = appointmentService;
        this.patientService = patientService;
        this.patientDataAccessService = patientDataAccessService;
        this.facilityWorkflowConfigService = facilityWorkflowConfigService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    private static String formatInstant(Instant value) {
        return value != null ? value.toString() : null;
    }

    private AppointmentView toView(Appointment appointment) {
        String patientName = null;
        try {
            patientName = patientService.findById(appointment.getPatientId()).getFullName();
        } catch (RuntimeException ignored) {
            // Keep response resilient even if patient lookup fails for historical records.
        }

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
                appointment.getPatientId(),
                patientName,
                appointment.getStatus() != null ? appointment.getStatus().name() : null,
                formatInstant(appointment.getScheduledAt()),
                formatInstant(appointment.getCheckInWindowOpensAt()),
                formatInstant(appointment.getCheckInWindowClosesAt()),
                appointment.canCheckInAt(Instant.now()),
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

    @PostMapping("/schedule")
    public ResponseEntity<?> scheduleAppointment(@RequestBody ScheduleRequest request) {
        try {
            auditGuard.assertSessionActive();
            Appointment appointment = appointmentService.scheduleAppointment(new AppointmentService.ScheduleInput(
                    request.patientId(),
                    request.scheduledAt(),
                    request.durationMinutes(),
                    request.clinicianId(),
                    request.clinicianName(),
                    request.clinicianEmployeeId(),
                    request.departmentCode(),
                    request.departmentName(),
                    request.reason()
            ));
            log.info("Appointment scheduled via API appointmentId={} patientId={}", appointment.getId(), appointment.getPatientId());
            return ResponseEntity.ok(toView(appointment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/today")
    public ResponseEntity<?> getTodayAppointments() {
        try {
            auditGuard.assertSessionActive();
            List<AppointmentView> appointments = appointmentService.getTodayAppointments().stream()
                    .map(this::toView)
                    .toList();
            return ResponseEntity.ok(appointments);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/patient/{patientId}/pending")
    public ResponseEntity<?> getPendingByPatient(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            List<AppointmentView> pending = appointmentService.getPatientPendingAppointments(patientId).stream()
                    .map(this::toView)
                    .toList();
            return ResponseEntity.ok(pending);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{appointmentId}/checkin")
    public ResponseEntity<?> checkInAppointmentByStaff(
            @PathVariable UUID appointmentId,
            @RequestBody(required = false) StaffCheckInRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            if (request == null || request.patientId() == null) {
                return ResponseEntity.badRequest().body(new ErrorResponse("patientId is required"));
            }

            if (Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(request.patientId());
            }

            AppointmentService.AppointmentCheckInResult result = appointmentService.checkInAppointment(
                    request.patientId(),
                    appointmentId,
                    request.complaint()
            );
            log.info("Appointment check-in via staff API appointmentId={} patientId={}",
                    appointmentId, request.patientId());

            return ResponseEntity.ok(new CheckInResponse(
                    toView(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{appointmentId}/cancel")
    public ResponseEntity<?> cancelAppointment(
            @PathVariable UUID appointmentId,
            @RequestBody(required = false) CancelRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            Appointment appointment = appointmentService.cancelAppointment(
                    appointmentId,
                    request != null ? request.reason() : null
            );
            log.info("Appointment cancelled via API appointmentId={} patientId={}", appointmentId, appointment.getPatientId());
            return ResponseEntity.ok(toView(appointment));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/assigned/pending")
    public ResponseEntity<?> getAssignedPendingAppointments() {
        try {
            auditGuard.assertSessionActive();
            Role role = sessionContext.role();
            if (role != Role.PHYSICIAN && role != Role.NURSE && role != Role.ADMIN && role != Role.SUPER_ADMIN) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Current role cannot view assigned appointments"));
            }
            List<AppointmentView> assigned = appointmentService.getAssignedPendingAppointmentsForCurrentClinician().stream()
                    .map(this::toView)
                    .toList();
            return ResponseEntity.ok(assigned);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record ScheduleRequest(
            UUID patientId,
            Instant scheduledAt,
            Integer durationMinutes,
            UUID clinicianId,
            String clinicianName,
            String clinicianEmployeeId,
            String departmentCode,
            String departmentName,
            String reason
    ) {
    }

    public record StaffCheckInRequest(
            UUID patientId,
            String complaint,
            Boolean consentForDataAccess
    ) {
    }

    public record CancelRequest(String reason) {
    }

    public record CheckInResponse(
            AppointmentView appointment,
            QueueController.TicketResponse queueTicket
    ) {
    }

    public record AppointmentView(
            UUID id,
            String appointmentNumber,
            UUID patientId,
            String patientName,
            String status,
            String scheduledAt,
            String checkInWindowOpensAt,
            String checkInWindowClosesAt,
            boolean checkInEligibleNow,
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

    public record ErrorResponse(String error) {
    }
}
