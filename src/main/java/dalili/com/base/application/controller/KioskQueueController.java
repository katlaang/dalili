package dalili.com.base.application.controller;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.QueueService;
import dalili.com.base.application.service.SessionActivityService;
import dalili.com.base.domain.queue.QueueTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for kiosk self-service operations.
 *
 * <p>This controller provides endpoints for patient self-check-in at kiosk
 * devices. The workflow is:
 * <ol>
 *   <li>Patient authenticates at kiosk with MRN + DOB</li>
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

    private final QueueService queueService;
    private final SessionActivityService sessionActivityService;
    private final SessionContext sessionContext;

    /**
     * Constructs a new KioskQueueController.
     *
     * @param queueService           the queue service
     * @param sessionActivityService the session activity service
     * @param sessionContext         the session context
     */
    public KioskQueueController(
            QueueService queueService,
            SessionActivityService sessionActivityService,
            SessionContext sessionContext
    ) {
        this.queueService = queueService;
        this.sessionActivityService = sessionActivityService;
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
                    "Patient ID comes from the session (set during /api/auth/kiosk/checkin). " +
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

            // Mark kiosk session as used (single-use enforcement)
            UUID sessionId = sessionContext.sessionId();
            if (sessionId != null) {
                sessionActivityService.markKioskSessionUsed(sessionId);
            }

            return ResponseEntity.ok(new CheckInResponse(
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getCategory().getDisplayName(),
                    ticket.getTriageLevel().getDisplayName(),
                    ticket.getTargetWaitMinutes(),
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
        var categories = java.util.Arrays.stream(QueueTicket.QueueCategory.values())
                .map(c -> new CategoryInfo(c.name(), c.getDisplayName(), c.getPrefix()))
                .toList();
        return ResponseEntity.ok(categories);
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
            String complaint
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

            @Schema(description = "Instruction message for patient")
            String message
    ) {
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
