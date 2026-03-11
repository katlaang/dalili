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

    @Schema(description = "Appointment check-in response")
    public record AppointmentCheckInResponse(
            AppointmentView appointment,
            QueueController.TicketResponse queueTicket
    ) {
    }

    @Schema(description = "Appointment summary")
    public record AppointmentView(
            UUID id,
            String status,
            String scheduledAt,
            String checkInWindowOpensAt,
            String checkInWindowClosesAt,
            boolean checkInEligibleNow,
            String clinicianName,
            String clinicianEmployeeId,
            String departmentName
    ) {
        static AppointmentView from(Appointment appointment) {
            return new AppointmentView(
                    appointment.getId(),
                    appointment.getStatus() != null ? appointment.getStatus().name() : null,
                    appointment.getScheduledAt() != null ? appointment.getScheduledAt().toString() : null,
                    appointment.getCheckInWindowOpensAt() != null ? appointment.getCheckInWindowOpensAt().toString() : null,
                    appointment.getCheckInWindowClosesAt() != null ? appointment.getCheckInWindowClosesAt().toString() : null,
                    appointment.canCheckInAt(Instant.now()),
                    appointment.getClinicianName(),
                    appointment.getClinicianEmployeeId(),
                    appointment.getDepartmentName()
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
