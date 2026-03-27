package dalili.com.base.application.controller;

import dalili.com.base.application.service.ClinicalAiService;
import dalili.com.base.application.service.QueueService;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for queue management operations.
 *
 * <p>This controller provides endpoints for:
 * <ul>
 *   <li>Issuing queue tickets at check-in</li>
 *   <li>Viewing queue status and statistics</li>
 *   <li>Calling patients from the queue</li>
 *   <li>Managing consultation workflow</li>
 *   <li>Priority escalation for deteriorating patients</li>
 * </ul>
 * </p>
 *
 * <p>Access is restricted to authorized clinical and administrative staff.</p>
 */
@RestController
@RequestMapping("/api/queue")
@Tag(name = "Queue Management", description = "APIs for managing patient queue and workflow")
public class QueueController {

    private static final Logger log = LoggerFactory.getLogger(QueueController.class);

    private final QueueService queueService;
    private final ClinicalAiService clinicalAiService;

    /**
     * Constructs a new QueueController.
     *
     * @param queueService the queue service
     */
    public QueueController(QueueService queueService, ClinicalAiService clinicalAiService) {
        this.queueService = queueService;
        this.clinicalAiService = clinicalAiService;
    }

    // ==================== TICKET ISSUANCE ====================

    /**
     * Issues a new queue ticket for a patient.
     *
     * @param request the ticket issue request
     * @return the created queue ticket
     */
    @Operation(
            summary = "Issue queue ticket",
            description = "Issues a new queue ticket for a patient at check-in. " +
                    "If the patient already has an active ticket for today, returns the existing ticket. " +
                    "Initial triage level is GREEN until nurse assessment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket issued successfully",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or patient not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/issue")
    public ResponseEntity<?> issueTicket(@RequestBody IssueTicketRequest request) {
        try {
            log.info("Queue issue request patientId={} category={}", request.patientId(), request.category());
            QueueTicket ticket = queueService.issueTicket(
                    request.patientId(),
                    request.category(),
                    request.initialComplaint()
            );
            log.info("Queue ticket issued ticketId={} patientId={}", ticket.getId(), ticket.getPatientId());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            log.warn("Queue issue failed patientId={} reason={}", request.patientId(), e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Issues an emergency queue ticket for ambulance arrivals.
     *
     * @param request the emergency ticket request
     * @return the created emergency queue ticket
     */
    @Operation(
            summary = "Issue emergency ticket",
            description = "Issues an emergency queue ticket for ambulance arrivals. " +
                    "Emergency tickets start at ORANGE triage level with AMBULANCE_ARRIVAL priority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Emergency ticket issued successfully",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/emergency")
    public ResponseEntity<?> issueEmergencyTicket(@RequestBody EmergencyTicketRequest request) {
        try {
            log.info("Emergency queue issue request patientId={}", request.patientId());
            QueueTicket ticket = queueService.issueEmergencyTicket(
                    request.patientId(),
                    request.initialComplaint()
            );
            log.info("Emergency queue ticket issued ticketId={} patientId={}", ticket.getId(), ticket.getPatientId());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            log.warn("Emergency queue issue failed patientId={} reason={}", request.patientId(), e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== QUEUE RETRIEVAL ====================

    /**
     * Gets the triage queue (patients waiting for nurse assessment).
     *
     * @return list of untriaged waiting tickets
     */
    @Operation(
            summary = "Get triage queue",
            description = "Returns all patients waiting to be triaged by a nurse, ordered by arrival time."
    )
    @ApiResponse(responseCode = "200", description = "Triage queue retrieved successfully")
    @GetMapping("/triage")
    public ResponseEntity<List<TicketResponse>> getTriageQueue() {
        List<TicketResponse> queue = queueService.getTriageQueue().stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(queue);
    }

    /**
     * Gets the consultation queue (triaged patients waiting for physician).
     *
     * @return list of triaged waiting tickets in priority order
     */
    @Operation(
            summary = "Get consultation queue",
            description = "Returns all triaged patients waiting for physician consultation, " +
                    "ordered by priority (RED first) then arrival time."
    )
    @ApiResponse(responseCode = "200", description = "Consultation queue retrieved successfully")
    @GetMapping("/consultation")
    public ResponseEntity<List<TicketResponse>> getConsultationQueue() {
        List<TicketResponse> queue = queueService.getConsultationQueue().stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(queue);
    }

    /**
     * Gets all waiting patients regardless of triage status.
     *
     * @return list of all waiting tickets in priority order
     */
    @Operation(
            summary = "Get full waiting queue",
            description = "Returns all waiting patients regardless of triage status, ordered by priority."
    )
    @ApiResponse(responseCode = "200", description = "Waiting queue retrieved successfully")
    @GetMapping("/waiting")
    public ResponseEntity<List<TicketResponse>> getWaitingQueue() {
        List<TicketResponse> queue = queueService.getWaitingQueue().stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(queue);
    }

    /**
     * Gets all tickets for today regardless of status.
     *
     * @return list of all today's tickets
     */
    @Operation(
            summary = "Get today's queue",
            description = "Returns all queue tickets for today regardless of status."
    )
    @ApiResponse(responseCode = "200", description = "Today's queue retrieved successfully")
    @GetMapping("/today")
    public ResponseEntity<List<TicketResponse>> getTodayQueue() {
        List<TicketResponse> queue = queueService.getTodayQueue().stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(queue);
    }

    /**
     * Gets tickets that have exceeded their target wait time.
     *
     * @return list of overdue tickets
     */
    @Operation(
            summary = "Get overdue tickets",
            description = "Returns all tickets that have exceeded their target wait time based on triage level."
    )
    @ApiResponse(responseCode = "200", description = "Overdue tickets retrieved successfully")
    @GetMapping("/overdue")
    public ResponseEntity<List<TicketResponse>> getOverdueTickets() {
        List<TicketResponse> queue = queueService.getOverdueTickets().stream()
                .map(TicketResponse::from)
                .toList();
        return ResponseEntity.ok(queue);
    }

    /**
     * Gets a specific ticket by ID.
     *
     * @param ticketId the ticket UUID
     * @return the ticket details
     */
    @Operation(
            summary = "Get ticket by ID",
            description = "Returns details for a specific queue ticket."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket found",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "404", description = "Ticket not found")
    })
    @GetMapping("/{ticketId}")
    public ResponseEntity<?> getTicket(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        return queueService.findById(ticketId)
                .map(ticket -> ResponseEntity.ok(TicketResponse.from(ticket)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Get triage outcome for queue ticket",
            description = "Returns triage outcome with queue impact and AI-assisted diagnosis suggestions for a queue ticket."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Triage outcome generated"),
            @ApiResponse(responseCode = "400", description = "Queue ticket or triage assessment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/triage-outcome")
    public ResponseEntity<?> getTriageOutcomeForTicket(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId,
            @RequestBody(required = false) OutcomeRequest request
    ) {
        try {
            String physicalExam = request != null ? request.physicalExam() : null;
            var outcome = clinicalAiService.generateTriageOutcomeForTicket(ticketId, physicalExam);
            return ResponseEntity.ok(outcome);
        } catch (ClinicalAiService.ClinicalAiException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Handoff triaged patient to clinician",
            description = "Captures triage nurse transfer to a specific physician/clinician with name and employee ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clinician handoff recorded",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid ticket state or missing handoff details",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/handoff")
    public ResponseEntity<?> handoffToClinician(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId,
            @RequestBody HandoffToClinicianRequest request
    ) {
        try {
            log.info("Queue handoff request ticketId={} clinicianEmployeeId={}", ticketId, request.clinicianEmployeeId());
            QueueTicket ticket = queueService.handoffToClinician(
                    ticketId,
                    new QueueService.ClinicianHandoffInput(
                            request.clinicianName(),
                            request.clinicianEmployeeId(),
                            request.clinicianUserId(),
                            request.handoffNotes()
                    )
            );
            log.info("Queue handoff completed ticketId={} patientId={}", ticket.getId(), ticket.getPatientId());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException | IllegalArgumentException e) {
            log.warn("Queue handoff failed ticketId={} reason={}", ticketId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Gets queue statistics for today.
     *
     * @return queue statistics
     */
    @Operation(
            summary = "Get queue statistics",
            description = "Returns comprehensive statistics for today's queue including " +
                    "counts by status and triage level."
    )
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    @GetMapping("/stats")
    public ResponseEntity<QueueService.QueueStats> getStats() {
        return ResponseEntity.ok(queueService.getTodayStats());
    }

    // ==================== PATIENT CALLING ====================

    /**
     * Calls the next patient for triage.
     *
     * @param request the call request with counter number
     * @return the called ticket
     */
    @Operation(
            summary = "Call next for triage",
            description = "Calls the next untriaged patient in arrival order. " +
                    "Used by nurses to bring patients for triage assessment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient called successfully",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "No patients waiting for triage",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/call-next/triage")
    public ResponseEntity<?> callNextForTriage(@RequestBody CallRequest request) {
        try {
            QueueTicket ticket = queueService.callNextForTriage(request.counterNumber());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Calls the next patient for consultation.
     *
     * @param request the call request with counter number
     * @return the called ticket
     */
    @Operation(
            summary = "Call next for consultation",
            description = "Calls the highest priority triaged patient for physician consultation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient called successfully",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "No triaged patients waiting",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/call-next/consultation")
    public ResponseEntity<?> callNextForConsultation(@RequestBody CallRequest request) {
        try {
            log.info("Queue call-next consultation request counter={}", request.counterNumber());
            QueueTicket ticket = queueService.callNextForConsultation(request.counterNumber());
            log.info("Queue call-next consultation success ticketId={} patientId={}", ticket.getId(), ticket.getPatientId());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            log.warn("Queue call-next consultation failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Calls a specific patient by ticket ID.
     *
     * @param ticketId the ticket UUID
     * @param request  the call request with counter number
     * @return the called ticket
     */
    @Operation(
            summary = "Call specific patient",
            description = "Calls a specific patient out of order. " +
                    "Used when patient returns after stepping out."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient called successfully",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or patient not waiting",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/call")
    public ResponseEntity<?> callPatient(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId,
            @RequestBody CallRequest request
    ) {
        try {
            QueueTicket ticket = queueService.callPatient(ticketId, request.counterNumber());
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Records that a called patient did not respond.
     *
     * @param ticketId the ticket UUID
     * @return the updated ticket
     */
    @Operation(
            summary = "Record missed call",
            description = "Records that a called patient did not respond. " +
                    "Patient returns to queue with missed call count incremented."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Missed call recorded",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Patient not in CALLED status",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/missed-call")
    public ResponseEntity<?> recordMissedCall(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.recordMissedCall(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== CONSULTATION WORKFLOW ====================

    /**
     * Starts a consultation/triage session.
     *
     * @param ticketId the ticket UUID
     * @return the updated ticket
     */
    @Operation(
            summary = "Start session",
            description = "Marks the start of a triage or consultation session when patient arrives."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session started",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Patient must be called first",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/start")
    public ResponseEntity<?> startConsultation(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.startConsultation(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Return triaged patient to waiting queue",
            description = "Returns a triaged ticket from CALLED/IN_PROGRESS back to WAITING so it can enter physician consultation queue."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Patient returned to waiting queue",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Ticket is not triaged or in invalid state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/return-to-waiting")
    public ResponseEntity<?> returnToWaitingQueue(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.returnToWaitingQueue(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Completes a consultation.
     *
     * @param ticketId the ticket UUID
     * @return the updated ticket
     */
    @Operation(
            summary = "Complete consultation",
            description = "Marks the completion of a consultation session."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consultation completed",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Session must be in progress",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/complete")
    public ResponseEntity<?> completeConsultation(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.completeConsultation(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Marks a patient as no-show.
     *
     * @param ticketId the ticket UUID
     * @return the updated ticket
     */
    @Operation(
            summary = "Mark no-show",
            description = "Marks a patient as no-show after failing to respond to calls."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "No-show recorded",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/no-show")
    public ResponseEntity<?> markNoShow(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.markNoShow(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(
            summary = "Mark admitted",
            description = "Marks a called or in-progress ticket as admitted for inpatient care."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Admission recorded",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or ticket state",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/admit")
    public ResponseEntity<?> admitPatient(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId,
            @RequestBody(required = false) AdmitRequest request
    ) {
        try {
            QueueTicket ticket = queueService.admitPatient(ticketId, request != null ? request.reason() : null);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Cancels a queue ticket.
     *
     * @param ticketId the ticket UUID
     * @return the updated ticket
     */
    @Operation(
            summary = "Cancel ticket",
            description = "Cancels a queue ticket."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket cancelled",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/cancel")
    public ResponseEntity<?> cancelTicket(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId
    ) {
        try {
            QueueTicket ticket = queueService.cancelTicket(ticketId);
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== PRIORITY ESCALATION ====================

    /**
     * Escalates a patient's priority.
     *
     * @param ticketId the ticket UUID
     * @param request  the escalation request
     * @return the updated ticket
     */
    @Operation(
            summary = "Escalate priority",
            description = "Escalates a waiting patient's priority due to condition deterioration. " +
                    "Requires clinical reason for audit purposes."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Priority escalated",
                    content = @Content(schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or patient not waiting",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{ticketId}/escalate")
    public ResponseEntity<?> escalatePriority(
            @Parameter(description = "Queue ticket UUID") @PathVariable UUID ticketId,
            @RequestBody EscalateRequest request
    ) {
        try {
            QueueTicket ticket = queueService.escalatePriority(
                    ticketId,
                    request.newTriageLevel(),
                    request.reason()
            );
            return ResponseEntity.ok(TicketResponse.from(ticket));
        } catch (QueueService.QueueException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== DTOs ====================

    /**
     * Request to issue a new queue ticket.
     *
     * @param patientId        the patient's UUID
     * @param category         the queue category
     * @param initialComplaint brief description of presenting complaint
     */
    @Schema(description = "Request to issue a new queue ticket")
    public record IssueTicketRequest(
            @Schema(description = "Patient UUID", required = true)
            UUID patientId,

            @Schema(description = "Queue category", required = true, example = "GENERAL")
            QueueTicket.QueueCategory category,

            @Schema(description = "Initial complaint from patient", example = "Headache and fever for 2 days")
            String initialComplaint
    ) {
    }

    /**
     * Request to issue an emergency queue ticket.
     *
     * @param patientId        the patient's UUID
     * @param initialComplaint description from paramedics
     */
    @Schema(description = "Request to issue an emergency queue ticket for ambulance arrivals")
    public record EmergencyTicketRequest(
            @Schema(description = "Patient UUID", required = true)
            UUID patientId,

            @Schema(description = "Initial complaint from paramedics", required = true,
                    example = "Motor vehicle accident, suspected fractures")
            String initialComplaint
    ) {
    }

    /**
     * Request to call a patient.
     *
     * @param counterNumber the counter/room number
     */
    @Schema(description = "Request to call a patient to a counter")
    public record CallRequest(
            @Schema(description = "Counter or room number", required = true, example = "Triage Room 1")
            String counterNumber
    ) {
    }

    /**
     * Request to escalate patient priority.
     *
     * @param newTriageLevel the new triage level
     * @param reason         clinical reason for escalation
     */
    @Schema(description = "Request to escalate patient priority")
    public record EscalateRequest(
            @Schema(description = "New triage level", required = true, example = "ORANGE")
            TriageLevel newTriageLevel,

            @Schema(description = "Clinical reason for escalation", required = true,
                    example = "Patient developing chest pain and shortness of breath")
            String reason
    ) {
    }

    @Schema(description = "Request to mark patient as admitted")
    public record AdmitRequest(
            @Schema(description = "Optional admission reason", example = "Requires inpatient monitoring for hypoxia")
            String reason
    ) {
    }

    @Schema(description = "Request to handoff patient to clinician after triage")
    public record HandoffToClinicianRequest(
            @Schema(description = "Assigned clinician display name", required = true, example = "Dr. Jane Doe")
            String clinicianName,

            @Schema(description = "Assigned clinician employee/staff ID", required = true, example = "PHY-1023")
            String clinicianEmployeeId,

            @Schema(description = "Assigned clinician user UUID (optional if known)")
            UUID clinicianUserId,

            @Schema(description = "Optional handoff notes from triage nurse")
            String handoffNotes
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

    @Schema(description = "Optional physical exam input for triage outcome generation")
    public record OutcomeRequest(
            @Schema(description = "Physical exam summary to improve suggested diagnoses")
            String physicalExam
    ) {
    }

    /**
     * Queue ticket response.
     */
    @Schema(description = "Queue ticket details")
    public record TicketResponse(
            @Schema(description = "Ticket UUID")
            UUID id,

            @Schema(description = "Patient UUID")
            UUID patientId,

            @Schema(description = "Queue date")
            LocalDate queueDate,

            @Schema(description = "Ticket number", example = "A-001")
            String ticketNumber,

            @Schema(description = "Date-stamped tracking reference", example = "20260314-WK-001")
            String trackingNumber,

            @Schema(description = "Permanent patient number (MRN)", example = "02459317")
            String patientNumber,

            @Schema(description = "Identifier to display in current workflow step")
            String workflowNumber,

            @Schema(description = "Queue category")
            QueueTicket.QueueCategory category,

            @Schema(description = "Triage level")
            TriageLevel triageLevel,

            @Schema(description = "Current status")
            QueueTicket.QueueStatus status,

            @Schema(description = "Whether triage assessment is complete")
            boolean triaged,

            @Schema(description = "Triage assessment UUID linked to this queue ticket")
            UUID triageAssessmentId,

            @Schema(description = "Effective queue priority value (lower is higher priority)")
            int effectivePriority,

            @Schema(description = "Initial complaint")
            String initialComplaint,

            @Schema(description = "Latest triage summary captured for queue display")
            String triageSummary,

            @Schema(description = "Top suggested diagnosis from AI triage outcome")
            String suggestedPrimaryDiagnosis,

            @Schema(description = "Flattened suggested diagnoses from AI triage outcome")
            String suggestedDiagnoses,

            @Schema(description = "Target wait time in minutes based on triage level")
            int targetWaitMinutes,

            @Schema(description = "Whether patient has exceeded target wait time")
            boolean overdue,

            @Schema(description = "Current wait time in minutes")
            long waitTimeMinutes,

            @Schema(description = "Number of missed calls")
            int missedCallCount,

            @Schema(description = "Whether patient arrived by ambulance")
            boolean ambulanceArrival,

            @Schema(description = "Check-in timestamp")
            Instant createdAt,

            @Schema(description = "Triage completion timestamp")
            Instant triagedAt,

            @Schema(description = "Called timestamp")
            Instant calledAt,

            @Schema(description = "Staff who called the patient")
            String calledByStaffName,

            @Schema(description = "Counter/room number")
            String counterNumber,

            @Schema(description = "Consultation start timestamp")
            Instant startedAt,

            @Schema(description = "Completion timestamp")
            Instant completedAt,

            @Schema(description = "Escalation reason if priority was escalated")
            String escalationReason,

            @Schema(description = "Admission reason if patient was admitted")
            String admissionReason,

            @Schema(description = "Linked appointment UUID if this is an appointment check-in")
            UUID appointmentId,

            @Schema(description = "Scheduled appointment timestamp")
            Instant appointmentScheduledAt,

            @Schema(description = "Appointment check-in window open timestamp")
            Instant appointmentWindowOpensAt,

            @Schema(description = "Appointment check-in window close timestamp")
            Instant appointmentWindowClosesAt,

            @Schema(description = "Whether appointment-time priority boost is active")
            boolean appointmentPriorityBoostApplied,

            @Schema(description = "When appointment-time priority boost was applied")
            Instant appointmentPriorityBoostAppliedAt,

            @Schema(description = "Assigned clinician user UUID")
            UUID assignedClinicianUserId,

            @Schema(description = "Assigned clinician display name")
            String assignedClinicianName,

            @Schema(description = "Assigned clinician employee/staff ID")
            String assignedClinicianEmployeeId,

            @Schema(description = "Assignment source (NURSE_HANDOFF, AUTO_REPEAT_MATCH, DIRECT_CALL)")
            String clinicianAssignmentSource,

            @Schema(description = "Optional handoff notes from triage nurse")
            String clinicianHandoffNotes,

            @Schema(description = "When clinician assignment was captured")
            Instant clinicianAssignedAt,

            @Schema(description = "Staff ID of handoff initiator")
            String clinicianAssignedByStaffId,

            @Schema(description = "Staff name of handoff initiator")
            String clinicianAssignedByStaffName,

            @Schema(description = "When assigned clinician accepted handoff by calling patient")
            Instant clinicianHandoffAcceptedAt,

            @Schema(description = "Clinician staff ID that accepted handoff")
            String clinicianHandoffAcceptedByStaffId,

            @Schema(description = "Clinician name that accepted handoff")
            String clinicianHandoffAcceptedByStaffName
    ) {
        /**
         * Creates a TicketResponse from a QueueTicket entity.
         *
         * @param t the queue ticket
         * @return the response DTO
         */
        public static TicketResponse from(QueueTicket t) {
            return new TicketResponse(
                    t.getId(),
                    t.getPatientId(),
                    t.getQueueDate(),
                    t.getTicketNumber(),
                    t.getTrackingNumber(),
                    t.getPatientMrnSnapshot(),
                    resolveWorkflowNumber(t),
                    t.getCategory(),
                    t.getTriageLevel(),
                    t.getStatus(),
                    t.isTriaged(),
                    t.getTriageAssessmentId(),
                    t.getEffectivePriority(),
                    t.getInitialComplaint(),
                    t.getTriageSummary(),
                    t.getSuggestedPrimaryDiagnosis(),
                    t.getSuggestedDiagnoses(),
                    t.getTargetWaitMinutes(),
                    t.isOverdue(),
                    t.getWaitTimeMinutes(),
                    t.getMissedCallCount(),
                    t.isAmbulanceArrival(),
                    t.getCreatedAt(),
                    t.getTriagedAt(),
                    t.getCalledAt(),
                    t.getCalledByStaffName(),
                    t.getCounterNumber(),
                    t.getStartedAt(),
                    t.getCompletedAt(),
                    t.getEscalationReason(),
                    t.getAdmissionReason(),
                    t.getAppointmentId(),
                    t.getAppointmentScheduledAt(),
                    t.getAppointmentWindowOpensAt(),
                    t.getAppointmentWindowClosesAt(),
                    t.isAppointmentPriorityBoostApplied(),
                    t.getAppointmentPriorityBoostAppliedAt(),
                    t.getAssignedClinicianUserId(),
                    t.getAssignedClinicianName(),
                    t.getAssignedClinicianEmployeeId(),
                    t.getClinicianAssignmentSource(),
                    t.getClinicianHandoffNotes(),
                    t.getClinicianAssignedAt(),
                    t.getClinicianAssignedByStaffId(),
                    t.getClinicianAssignedByStaffName(),
                    t.getClinicianHandoffAcceptedAt(),
                    t.getClinicianHandoffAcceptedByStaffId(),
                    t.getClinicianHandoffAcceptedByStaffName()
            );
        }

        private static String resolveWorkflowNumber(QueueTicket ticket) {
            String patientNumber = ticket.getPatientMrnSnapshot();
            if (ticket.isTriaged() && patientNumber != null && !patientNumber.isBlank()) {
                return patientNumber;
            }

            String trackingNumber = ticket.getTrackingNumber();
            if (trackingNumber != null && !trackingNumber.isBlank()) {
                return trackingNumber;
            }
            return ticket.getTicketNumber();
        }
    }
}

