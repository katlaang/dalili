package dalili.com.base.application.controller;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.*;
import dalili.com.base.domain.appointment.model.Appointment;
import dalili.com.base.domain.queue.QueueTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller for kiosk self-service operations.
 *
 * <p>This controller provides endpoints for patient self-check-in at kiosk
 * devices. The workflow is:
 * <ol>
 *   <li>Patient is identified at kiosk (MRN + DOB or name + DOB via auth endpoint)</li>
 *   <li>Patient selects queue category and enters complaint</li>
 *   <li>System issues queue ticket</li>
 *   <li>Kiosk session is invalidated (single-use)</li>
 * </ol>
 * </p>
 *
 * <p>All kiosk operations are audited. Kiosk tokens are single-use for security.</p>
 */
@RestController
@RequestMapping("/api/kiosk")
@Tag(name = "Kiosk Self-Service", description = "APIs for patient self-check-in at kiosk devices")
public class KioskQueueController {

    private static final Logger log = LoggerFactory.getLogger(KioskQueueController.class);

    private final PatientService patientService;
    private final QueueService queueService;
    private final PatientDataAccessService patientDataAccessService;
    private final SessionActivityService sessionActivityService;
    private final FacilityWorkflowConfigService facilityWorkflowConfigService;
    private final AppointmentService appointmentService;
    private final SessionContext sessionContext;

    /**
     * Constructs a new KioskQueueController.
     *
     * @param queueService           the queue service
     * @param sessionActivityService the session activity service
     * @param sessionContext         the session context
     */
    public KioskQueueController(
            PatientService patientService,
            QueueService queueService,
            PatientDataAccessService patientDataAccessService,
            SessionActivityService sessionActivityService,
            FacilityWorkflowConfigService facilityWorkflowConfigService,
            AppointmentService appointmentService,
            SessionContext sessionContext
    ) {
        this.patientService = patientService;
        this.queueService = queueService;
        this.patientDataAccessService = patientDataAccessService;
        this.sessionActivityService = sessionActivityService;
        this.facilityWorkflowConfigService = facilityWorkflowConfigService;
        this.appointmentService = appointmentService;
        this.sessionContext = sessionContext;
    }

    /**
     * Issues a queue ticket for the authenticated patient.
     *
     * <p>The patient ID is retrieved from the session context, which was
     * set during kiosk authentication. After issuing the ticket, the kiosk
     * session is marked as used (single-use enforcement).</p>
     *
     * @param request the check-in request
     * @return the queue ticket details
     */
    @Operation(
            summary = "Complete kiosk check-in",
            description = "Issues a queue ticket for the patient authenticated at the kiosk. " +
                    "Patient ID comes from the session (set during kiosk auth identify/check-in). " +
                    "This is a single-use operation - the kiosk token is invalidated after."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Check-in successful",
                    content = @Content(schema = @Schema(implementation = CheckInResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or no patient in session",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid or expired kiosk token")
    })
    @PostMapping("/queue/checkin")
    public ResponseEntity<?> checkIn(@RequestBody CheckInRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();

            // Get patient ID from session (set during kiosk authentication)
            UUID patientId = sessionContext.patientId();
            if (patientId == null) {
                return ResponseEntity.badRequest().body(
                        new ErrorResponse("No patient in session. Please authenticate first.")
                );
            }

            // Issue queue ticket
            QueueTicket ticket = queueService.issueTicket(
                    patientId,
                    request.category(),
                    request.complaint()
            );
            log.info("Kiosk queue check-in completed ticketId={} patientId={} category={}",
                    ticket.getId(), patientId, request.category());
            var patient = patientService.findById(patientId);
            boolean consentRecorded = false;
            if (request.consentForDataAccess() != null && request.consentForDataAccess()) {
                patientDataAccessService.recordPatientConsent(patientId);
                consentRecorded = true;
            }

            // Mark kiosk session as used (single-use enforcement)
            UUID sessionId = sessionContext.sessionId();
            if (sessionId != null) {
                sessionActivityService.markKioskSessionUsed(sessionId);
            }

            return ResponseEntity.ok(new CheckInResponse(
                    patient.getId(),
                    patient.getMrn(),
                    patient.getFullName(),
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getCategory().getDisplayName(),
                    ticket.getTriageLevel().getDisplayName(),
                    ticket.getTargetWaitMinutes(),
                    consentRecorded,
                    "Please proceed to the waiting area. " +
                            "Your number will be called for triage assessment."
            ));

        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Gets available queue categories for display on kiosk.
     *
     * @return list of available categories
     */
    @Operation(
            summary = "Get queue categories",
            description = "Returns available queue categories for kiosk display."
    )
    @ApiResponse(responseCode = "200", description = "Categories retrieved")
    @GetMapping("/queue/categories")
    public ResponseEntity<?> getCategories() {
        var config = facilityWorkflowConfigService.getCurrentConfig();
        var categories = java.util.Arrays.stream(QueueTicket.QueueCategory.values())
                .filter(category -> config.isEmergencyFlowEnabled() || category != QueueTicket.QueueCategory.EMERGENCY)
                .filter(category -> config.isAppointmentFlowEnabled() || category != QueueTicket.QueueCategory.FOLLOW_UP)
                .map(c -> new CategoryInfo(c.name(), c.getDisplayName(), c.getPrefix()))
                .toList();
        return ResponseEntity.ok(categories);
    }

    @Operation(
            summary = "Record patient consent from kiosk session",
            description = "Captures patient consent for same-hospital historical data access."
    )
    @PostMapping("/patient/consent")
    public ResponseEntity<?> recordConsent() {
        UUID patientId = sessionContext.patientId();
        if (patientId == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("No patient in kiosk session"));
        }
        var authorization = patientDataAccessService.recordPatientConsent(patientId);
        log.info("Kiosk consent recorded patientId={} authorizationId={}", patientId, authorization.getId());
        return ResponseEntity.ok(new ConsentResponse(
                authorization.getId(),
                authorization.getDataAccessScope().name(),
                authorization.getAuthorizationType().name(),
                authorization.getGrantedAt().toString()
        ));
    }

    @Operation(
            summary = "Get pending appointments for kiosk patient",
            description = "Returns pending appointments linked to the patient authenticated in kiosk session."
    )
    @GetMapping("/appointments/pending")
    public ResponseEntity<?> getPendingAppointments() {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();
            UUID patientId = sessionContext.patientId();
            if (patientId == null) {
                return ResponseEntity.badRequest().body(new ErrorResponse("No patient in kiosk session"));
            }
            var pending = appointmentService.getPatientPendingAppointments(patientId).stream()
                    .map(AppointmentView::from)
                    .toList();
            log.info("Kiosk pending appointments retrieved patientId={} count={}", patientId, pending.size());
            return ResponseEntity.ok(pending);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Verify appointment access for kiosk",
            description = "Validates patient appointment access using patient ID + access code or QR token."
    )
    @PostMapping("/appointments/verify")
    public ResponseEntity<?> verifyAppointments(@RequestBody AppointmentVerificationRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();
            var pending = appointmentService.getKioskPendingAppointments(
                            request.patientId(),
                            request.accessCode(),
                            request.qrToken()
                    ).stream()
                    .map(AppointmentView::from)
                    .toList();
            if (pending.isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("No pending appointments found for provided credentials"));
            }
            return ResponseEntity.ok(pending);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Check-in pending appointment from kiosk",
            description = "Checks in a pending appointment and creates appointment-linked queue ticket."
    )
    @PostMapping("/appointments/{appointmentId}/checkin")
    public ResponseEntity<?> checkInAppointment(
            @PathVariable UUID appointmentId,
            @RequestBody(required = false) AppointmentCheckInRequest request
    ) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();
            UUID patientId = sessionContext.patientId();
            if (patientId == null) {
                return ResponseEntity.badRequest().body(new ErrorResponse("No patient in kiosk session"));
            }
            if (request != null && Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(patientId);
            }
            var result = appointmentService.checkInAppointment(
                    patientId,
                    appointmentId,
                    request != null ? request.complaint() : null
            );
            log.info("Kiosk appointment check-in completed appointmentId={} patientId={} queueTicketId={}",
                    appointmentId, patientId, result.queueTicket().getId());
            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    AppointmentView.from(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Confirm appointment and issue kiosk queue number",
            description = "Confirms patient identity for a specific appointment using access code or QR token, " +
                    "then checks in and returns queue ticket."
    )
    @PostMapping("/appointments/confirm")
    public ResponseEntity<?> confirmAppointmentCheckIn(@RequestBody AppointmentConfirmRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();
            if (Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(request.patientId());
            }
            var result = appointmentService.checkInAppointmentFromKiosk(
                    request.patientId(),
                    request.appointmentId(),
                    request.accessCode(),
                    request.qrToken(),
                    request.complaint()
            );
            log.info("Kiosk appointment confirmed appointmentId={} patientId={} queueTicketId={}",
                    request.appointmentId(), request.patientId(), result.queueTicket().getId());
            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    AppointmentView.from(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Confirm appointment by appointment number and issue queue number",
            description = "Checks in a scheduled appointment using appointment number only and returns queue ticket."
    )
    @PostMapping("/appointments/confirm-by-number")
    public ResponseEntity<?> confirmAppointmentByNumber(@RequestBody AppointmentNumberConfirmRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();

            var result = appointmentService.checkInAppointmentByNumberFromKiosk(
                    request.appointmentNumber(),
                    request.givenName(),
                    request.familyName(),
                    java.time.LocalDate.parse(request.dateOfBirth()),
                    request.complaint()
            );

            if (Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(result.appointment().getPatientId());
            }

            log.info("Kiosk appointment confirmed by number appointmentId={} appointmentNumber={} queueTicketId={}",
                    result.appointment().getId(), result.appointment().getAppointmentNumber(), result.queueTicket().getId());

            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    AppointmentView.from(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Public kiosk appointment confirmation",
            description = "No-auth endpoint for kiosk stations. Confirms appointment by appointment number + name + DOB and issues queue number."
    )
    @PostMapping("/public/appointments/confirm-by-number")
    public ResponseEntity<?> publicConfirmAppointmentByNumber(@RequestBody PublicAppointmentCheckInRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();

            var result = appointmentService.checkInAppointmentByNumberFromKiosk(
                    request.appointmentNumber(),
                    request.givenName(),
                    request.familyName(),
                    java.time.LocalDate.parse(request.dateOfBirth()),
                    request.complaint()
            );

            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    AppointmentView.from(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Public kiosk appointment confirmation by QR token",
            description = "No-auth endpoint for kiosk stations. Confirms appointment by QR token + name + DOB and issues queue number."
    )
    @PostMapping("/public/appointments/confirm-by-qr")
    public ResponseEntity<?> publicConfirmAppointmentByQr(@RequestBody PublicAppointmentQrCheckInRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();

            var result = appointmentService.checkInAppointmentByQrFromKiosk(
                    request.qrToken(),
                    request.givenName(),
                    request.familyName(),
                    java.time.LocalDate.parse(request.dateOfBirth()),
                    request.complaint()
            );

            return ResponseEntity.ok(new AppointmentCheckInResponse(
                    AppointmentView.from(result.appointment()),
                    QueueController.TicketResponse.from(result.queueTicket())
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Public kiosk no-appointment check-in",
            description = "No-auth endpoint for kiosk stations. Captures name + DOB + complaint and issues queue number in GENERAL queue."
    )
    @PostMapping("/public/queue/checkin")
    public ResponseEntity<?> publicNoAppointmentCheckIn(@RequestBody PublicNoAppointmentCheckInRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            var patient = patientService.resolveOrRegisterForKioskCheckIn(
                    request.givenName(),
                    request.familyName(),
                    java.time.LocalDate.parse(request.dateOfBirth()),
                    dalili.com.base.domain.patient.model.Patient.Sex.UNKNOWN
            );

            QueueTicket ticket = queueService.issueTicket(
                    patient.getId(),
                    QueueTicket.QueueCategory.GENERAL,
                    request.complaint()
            );

            return ResponseEntity.ok(new CheckInResponse(
                    patient.getId(),
                    patient.getMrn(),
                    patient.getFullName(),
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getCategory().getDisplayName(),
                    ticket.getTriageLevel().getDisplayName(),
                    ticket.getTargetWaitMinutes(),
                    false,
                    "Please proceed to the waiting area. Your number will be called for triage assessment."
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Kiosk no-appointment check-in",
            description = "Identifies (or auto-registers) a patient by demographics and issues queue number without an appointment."
    )
    @PostMapping("/queue/no-appointment-checkin")
    public ResponseEntity<?> noAppointmentCheckIn(@RequestBody NoAppointmentCheckInRequest request) {
        try {
            facilityWorkflowConfigService.assertKioskEnabled();
            var patient = patientService.resolveOrRegisterForKioskCheckIn(
                    request.givenName(),
                    request.familyName(),
                    java.time.LocalDate.parse(request.dateOfBirth()),
                    request.sex()
            );
            if (Boolean.TRUE.equals(request.consentForDataAccess())) {
                patientDataAccessService.recordPatientConsent(patient.getId());
            }
            QueueTicket ticket = queueService.issueTicket(
                    patient.getId(),
                    request.category(),
                    request.complaint()
            );
            log.info("Kiosk no-appointment check-in completed ticketId={} patientId={} category={}",
                    ticket.getId(), patient.getId(), request.category());
            return ResponseEntity.ok(new CheckInResponse(
                    patient.getId(),
                    patient.getMrn(),
                    patient.getFullName(),
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getCategory().getDisplayName(),
                    ticket.getTriageLevel().getDisplayName(),
                    ticket.getTargetWaitMinutes(),
                    Boolean.TRUE.equals(request.consentForDataAccess()),
                    "Please proceed to the waiting area. Your number will be called for triage assessment."
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== DTOs ====================

    /**
     * Request to complete kiosk check-in.
     *
     * @param category  the queue category
     * @param complaint brief description of presenting complaint
     */
    @Schema(description = "Kiosk check-in request")
    public record CheckInRequest(
            @Schema(description = "Queue category", required = true, example = "GENERAL")
            QueueTicket.QueueCategory category,

            @Schema(description = "Brief complaint description",
                    example = "Headache and fever", maxLength = 500)
            String complaint,

            @Schema(description = "Whether patient provides consent for historical data access")
            Boolean consentForDataAccess
    ) {
    }

    /**
     * Response after successful kiosk check-in.
     *
     * @param ticketId             the queue ticket UUID
     * @param ticketNumber         the display ticket number (e.g., "A-001")
     * @param category             the category display name
     * @param triageLevel          the initial triage level
     * @param estimatedWaitMinutes estimated wait based on triage
     * @param message              instruction message for patient
     */
    @Schema(description = "Kiosk check-in response")
    public record CheckInResponse(
            @Schema(description = "Patient UUID")
            UUID patientId,

            @Schema(description = "Patient MRN", example = "KIO-1A2B3C4D")
            String patientMrn,

            @Schema(description = "Patient full name", example = "Jane Doe")
            String patientName,

            @Schema(description = "Queue ticket UUID")
            UUID ticketId,

            @Schema(description = "Ticket number for display", example = "A-001")
            String ticketNumber,

            @Schema(description = "Queue category", example = "General")
            String category,

            @Schema(description = "Initial triage level", example = "Standard")
            String triageLevel,

            @Schema(description = "Estimated wait time in minutes", example = "120")
            int estimatedWaitMinutes,

            @Schema(description = "Whether consent was captured during check-in")
            boolean consentRecorded,

            @Schema(description = "Instruction message for patient")
            String message
    ) {
    }

    @Schema(description = "Consent recording response")
    public record ConsentResponse(
            UUID authorizationId,
            String scope,
            String authorizationType,
            String grantedAt
    ) {
    }

    @Schema(description = "Appointment check-in request from kiosk")
    public record AppointmentCheckInRequest(
            @Schema(description = "Optional complaint captured at check-in")
            String complaint,

            @Schema(description = "Consent flag for same-hospital data access at check-in")
            Boolean consentForDataAccess
    ) {
    }

    @Schema(description = "Appointment verification request")
    public record AppointmentVerificationRequest(
            @Schema(description = "Patient UUID", required = true)
            UUID patientId,

            @Schema(description = "Appointment access code received via patient portal/email", example = "483920")
            String accessCode,

            @Schema(description = "Appointment QR token received via patient portal/email")
            String qrToken
    ) {
    }

    @Schema(description = "Appointment confirmation request")
    public record AppointmentConfirmRequest(
            @Schema(description = "Patient UUID", required = true)
            UUID patientId,

            @Schema(description = "Appointment UUID to confirm", required = true)
            UUID appointmentId,

            @Schema(description = "Appointment access code")
            String accessCode,

            @Schema(description = "Appointment QR token")
            String qrToken,

            @Schema(description = "Optional complaint captured at check-in")
            String complaint,

            @Schema(description = "Consent flag for same-hospital data access")
            Boolean consentForDataAccess
    ) {
    }

    @Schema(description = "Appointment check-in request by appointment number")
    public record AppointmentNumberConfirmRequest(
            @Schema(description = "Appointment number", required = true, example = "PR-001")
            String appointmentNumber,

            @Schema(description = "Given name", required = true)
            String givenName,

            @Schema(description = "Family name", required = true)
            String familyName,

            @Schema(description = "Date of birth (YYYY-MM-DD)", required = true)
            String dateOfBirth,

            @Schema(description = "Optional complaint captured at check-in")
            String complaint,

            @Schema(description = "Consent flag for same-hospital data access")
            Boolean consentForDataAccess
    ) {
    }

    @Schema(description = "Public kiosk appointment check-in request")
    public record PublicAppointmentCheckInRequest(
            @Schema(description = "Appointment number", required = true, example = "PR-001")
            String appointmentNumber,

            @Schema(description = "Given name", required = true)
            String givenName,

            @Schema(description = "Family name", required = true)
            String familyName,

            @Schema(description = "Date of birth (YYYY-MM-DD)", required = true)
            String dateOfBirth,

            @Schema(description = "Optional complaint")
            String complaint
    ) {
    }

    @Schema(description = "Public kiosk appointment QR check-in request")
    public record PublicAppointmentQrCheckInRequest(
            @Schema(description = "Appointment QR token", required = true)
            String qrToken,

            @Schema(description = "Given name", required = true)
            String givenName,

            @Schema(description = "Family name", required = true)
            String familyName,

            @Schema(description = "Date of birth (YYYY-MM-DD)", required = true)
            String dateOfBirth,

            @Schema(description = "Optional complaint")
            String complaint
    ) {
    }

    @Schema(description = "Public kiosk no-appointment check-in request")
    public record PublicNoAppointmentCheckInRequest(
            @Schema(description = "Given name", required = true)
            String givenName,

            @Schema(description = "Family name", required = true)
            String familyName,

            @Schema(description = "Date of birth (YYYY-MM-DD)", required = true)
            String dateOfBirth,

            @Schema(description = "Optional complaint")
            String complaint
    ) {
    }

    @Schema(description = "No-appointment kiosk check-in request")
    public record NoAppointmentCheckInRequest(
            @Schema(description = "Given name", required = true)
            String givenName,

            @Schema(description = "Family name", required = true)
            String familyName,

            @Schema(description = "Date of birth (YYYY-MM-DD)", required = true)
            String dateOfBirth,

            @Schema(description = "Patient sex", required = true)
            dalili.com.base.domain.patient.model.Patient.Sex sex,

            @Schema(description = "Queue category", required = true)
            QueueTicket.QueueCategory category,

            @Schema(description = "Optional complaint")
            String complaint,

            @Schema(description = "Consent flag for same-hospital data access")
            Boolean consentForDataAccess
    ) {
    }

    @Schema(description = "Appointment check-in response")
    public record AppointmentCheckInResponse(
            AppointmentView appointment,
            QueueController.TicketResponse queueTicket
    ) {
    }

    @Schema(description = "Appointment summary")
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
            String reason
    ) {
        static AppointmentView from(Appointment appointment) {
            return new AppointmentView(
                    appointment.getId(),
                    appointment.getAppointmentNumber(),
                    appointment.getStatus() != null ? appointment.getStatus().name() : null,
                    appointment.getScheduledAt() != null ? appointment.getScheduledAt().toString() : null,
                    appointment.getCheckInWindowOpensAt() != null ? appointment.getCheckInWindowOpensAt().toString() : null,
                    appointment.getCheckInWindowClosesAt() != null ? appointment.getCheckInWindowClosesAt().toString() : null,
                    appointment.canCheckInAt(Instant.now()),
                    appointment.getKioskAccessCode(),
                    appointment.getKioskQrToken(),
                    appointment.getClinicianName(),
                    appointment.getClinicianEmployeeId(),
                    appointment.getDepartmentName(),
                    appointment.getFacilityCode(),
                    appointment.getFacilityName(),
                    appointment.getReason()
            );
        }
    }

    /**
     * Queue category information for display.
     *
     * @param code        the category code
     * @param displayName the display name
     * @param prefix      the ticket prefix
     */
    @Schema(description = "Queue category information")
    public record CategoryInfo(
            @Schema(description = "Category code", example = "GENERAL")
            String code,

            @Schema(description = "Display name", example = "General")
            String displayName,

            @Schema(description = "Ticket prefix", example = "A")
            String prefix
    ) {
    }

    /**
     * Error response.
     *
     * @param error the error message
     */
    @Schema(description = "Error response")
    public record ErrorResponse(
            @Schema(description = "Error message")
            String error
    ) {
    }
}
