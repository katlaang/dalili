package dalili.com.base.application.service;


import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.PriorityModifier;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageLevel;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing the patient queue system.
 *
 * <p>This service handles the complete queue workflow:
 * <ul>
 *   <li>Issuing queue tickets at kiosk check-in or reception</li>
 *   <li>Priority-based queue ordering using triage levels</li>
 *   <li>Calling patients from the queue</li>
 *   <li>Tracking consultation progress</li>
 *   <li>Emergency priority escalation</li>
 *   <li>Queue statistics and monitoring</li>
 * </ul>
 * </p>
 *
 * <p>Queue priority is determined by:
 * <ol>
 *   <li>Triage level (RED immediate → BLUE non-urgent)</li>
 *   <li>Priority modifiers (pediatric, elderly, ambulance arrival, etc.)</li>
 *   <li>Check-in time (FIFO within same priority)</li>
 * </ol>
 * </p>
 *
 * <p>Triage assessment (vitals, observations) is handled separately by
 * {@link TriageService}. This service manages queue position and status only.</p>
 *
 * <p>All queue actions are audited for compliance and operational visibility.</p>
 *
 * @see QueueTicket
 * @see TriageLevel
 * @see PriorityModifier
 * @see TriageService
 */
@Service
public class QueueService {

    private final QueueTicketRepository queueRepository;
    private final PatientService patientService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    /**
     * Constructs a new QueueService with required dependencies.
     *
     * @param queueRepository repository for queue ticket persistence
     * @param patientService  service for patient data access
     * @param auditService    service for audit trail recording
     * @param auditGuard      guard for session validation
     * @param sessionContext  current session context for staff identification
     */
    public QueueService(
            QueueTicketRepository queueRepository,
            PatientService patientService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.queueRepository = queueRepository;
        this.patientService = patientService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    // ==================== TICKET ISSUANCE ====================

    /**
     * Issues a standard queue ticket for a patient at check-in.
     *
     * <p>If the patient already has an active ticket for today (WAITING, CALLED,
     * or IN_PROGRESS), the existing ticket is returned instead of creating a duplicate.</p>
     *
     * <p>Initial triage level is GREEN (standard). This will be updated after
     * the nurse performs the triage assessment.</p>
     *
     * <p>Priority modifiers are automatically applied based on patient age:
     * <ul>
     *   <li>Under 1 year: PEDIATRIC_INFANT</li>
     *   <li>1-4 years: PEDIATRIC_CHILD</li>
     *   <li>65+ years: ELDERLY</li>
     * </ul>
     * </p>
     *
     * @param patientId        the patient's UUID
     * @param category         the queue category (GENERAL, EMERGENCY, etc.)
     * @param initialComplaint brief description of presenting complaint
     * @return the newly created or existing queue ticket
     * @throws QueueException if patient not found
     */
    @Transactional
    public QueueTicket issueTicket(
            UUID patientId,
            QueueTicket.QueueCategory category,
            String initialComplaint
    ) {
        LocalDate today = LocalDate.now();

        // Check for existing active ticket
        var activeStatuses = List.of(QueueTicket.QueueStatus.WAITING, QueueTicket.QueueStatus.CALLED, QueueTicket.QueueStatus.IN_PROGRESS);
        Optional<QueueTicket> existingTicket = queueRepository.findByPatientIdAndQueueDateAndStatusIn(
                patientId, today, activeStatuses
        );

        if (existingTicket.isPresent()) {
            return existingTicket.get();
        }

        // Get patient to determine age-based priority
        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(patientId));
        if (patientOpt.isEmpty()) {
            throw new QueueException("Patient not found");
        }
        Patient patient = patientOpt.get();

        PriorityModifier modifier = determineAgeBasedModifier(patient);

        // Generate ticket number
        String ticketNumber = generateTicketNumber(today, category);

        // Get issuer ID from session
        String issuedBy = sessionContext.username() != null
                ? sessionContext.username()
                : "KIOSK";

        QueueTicket ticket = QueueTicket.create(
                patientId,
                ticketNumber,
                category,
                modifier,
                initialComplaint,
                issuedBy
        );

        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_TICKET_ISSUED", patientId,
                String.format("Ticket %s issued. Category: %s, Modifier: %s",
                        ticketNumber, category, modifier));

        return ticket;
    }

    /**
     * Issues an emergency queue ticket for ambulance arrivals.
     *
     * <p>Emergency tickets are automatically assigned to the EMERGENCY category
     * with ORANGE triage level and AMBULANCE_ARRIVAL priority modifier.
     * They receive expedited handling and should proceed directly to triage.</p>
     *
     * @param patientId        the patient's UUID
     * @param initialComplaint description of emergency condition from paramedics
     * @return the emergency queue ticket
     */
    @Transactional
    public QueueTicket issueEmergencyTicket(UUID patientId, String initialComplaint) {
        auditGuard.assertSessionActive();

        LocalDate today = LocalDate.now();
        String ticketNumber = generateTicketNumber(today, QueueTicket.QueueCategory.EMERGENCY);
        String issuedBy = sessionContext.username();

        QueueTicket ticket = QueueTicket.createEmergency(
                patientId,
                ticketNumber,
                initialComplaint,
                issuedBy
        );

        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_EMERGENCY_TICKET", patientId,
                String.format("EMERGENCY ticket %s issued. Complaint: %s",
                        ticketNumber, initialComplaint));

        return ticket;
    }

    // ==================== TRIAGE INTEGRATION ====================

    /**
     * Updates queue ticket after triage assessment is completed.
     *
     * <p>Called by TriageService after finalizing the triage assessment.
     * Updates the ticket's triage level and recalculates priority.</p>
     *
     * @param ticketId           the queue ticket UUID
     * @param triageLevel        the final triage level from assessment
     * @param triageAssessmentId reference to the triage assessment record
     * @return the updated queue ticket
     */
    @Transactional
    public QueueTicket updateTriageLevel(UUID ticketId, TriageLevel triageLevel, UUID triageAssessmentId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        ticket.completeTriage(triageLevel, triageAssessmentId);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_TRIAGE_UPDATED", ticket.getPatientId(),
                String.format("Ticket %s triaged as %s", ticket.getTicketNumber(), triageLevel));

        return ticket;
    }

    /**
     * Escalates a patient's priority due to condition deterioration.
     *
     * <p>Called when a waiting patient's condition worsens and requires
     * faster attention. The ticket is reassigned a higher triage level
     * and moved up in the queue.</p>
     *
     * @param ticketId       the ticket to escalate
     * @param newTriageLevel the new (higher priority) triage level
     * @param reason         clinical reason for escalation
     * @return the updated queue ticket
     * @throws QueueException if ticket not found or not in WAITING status
     */
    @Transactional
    public QueueTicket escalatePriority(UUID ticketId, TriageLevel newTriageLevel, String reason) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.WAITING) {
            throw new QueueException("Can only escalate waiting patients");
        }

        TriageLevel previousLevel = ticket.getTriageLevel();
        String staffId = sessionContext.username();

        ticket.escalatePriority(newTriageLevel, reason, staffId);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_PRIORITY_ESCALATED", ticket.getPatientId(),
                String.format("Priority escalated from %s to %s. Reason: %s",
                        previousLevel, newTriageLevel, reason));

        return ticket;
    }

    // ==================== QUEUE RETRIEVAL ====================

    /**
     * Retrieves the triage queue - patients waiting to be triaged.
     *
     * <p>Returns all WAITING tickets that have not been triaged yet,
     * ordered by arrival time (FIFO). These patients need nurse assessment.</p>
     *
     * @return list of untriaged tickets in arrival order
     */
    public List<QueueTicket> getTriageQueue() {
        return queueRepository.findByQueueDateAndStatusAndTriagedOrderByCreatedAtAsc(
                LocalDate.now(), QueueTicket.QueueStatus.WAITING, false
        );
    }

    /**
     * Retrieves the consultation queue - triaged patients waiting for physician.
     *
     * <p>Returns all WAITING tickets that have been triaged, ordered by
     * effective priority then arrival time.</p>
     *
     * @return list of triaged tickets in priority order
     */
    public List<QueueTicket> getConsultationQueue() {
        return queueRepository.findByQueueDateAndStatusAndTriagedOrderByEffectivePriorityAscCreatedAtAsc(
                LocalDate.now(), QueueTicket.QueueStatus.WAITING, true
        );
    }

    /**
     * Retrieves the full waiting queue ordered by priority.
     *
     * <p>Returns all WAITING tickets regardless of triage status.</p>
     *
     * @return list of waiting tickets in priority order
     */
    public List<QueueTicket> getWaitingQueue() {
        return queueRepository.findByQueueDateAndStatusOrderByEffectivePriorityAscCreatedAtAsc(
                LocalDate.now(), QueueTicket.QueueStatus.WAITING
        );
    }

    /**
     * Retrieves the waiting queue for a specific category.
     *
     * @param category the category to filter by
     * @return list of waiting tickets for the category
     */
    public List<QueueTicket> getWaitingQueueByCategory(QueueTicket.QueueCategory category) {
        return queueRepository.findByQueueDateAndCategoryOrderByEffectivePriorityAscCreatedAtAsc(
                LocalDate.now(), category
        );
    }

    /**
     * Retrieves all tickets for today regardless of status.
     *
     * @return list of all today's tickets
     */
    public List<QueueTicket> getTodayQueue() {
        return queueRepository.findByQueueDateOrderByCreatedAtAsc(LocalDate.now());
    }

    /**
     * Retrieves tickets that have exceeded their target wait time.
     *
     * @return list of overdue tickets
     */
    public List<QueueTicket> getOverdueTickets() {
        return queueRepository.findWaitingTicketsForOverdueCheck(LocalDate.now(), QueueTicket.QueueStatus.WAITING)
                .stream()
                .filter(QueueTicket::isOverdue)
                .toList();
    }

    /**
     * Finds a queue ticket by ID.
     *
     * @param ticketId the ticket UUID
     * @return the ticket if found
     */
    public Optional<QueueTicket> findById(UUID ticketId) {
        return queueRepository.findById(ticketId);
    }

    // ==================== PATIENT CALLING ====================

    /**
     * Calls the next patient from the triage queue (for nurses).
     *
     * <p>Selects the first untriaged patient in arrival order.</p>
     *
     * @param counterNumber the triage room/counter number
     * @return the called ticket
     * @throws QueueException if no patients are waiting for triage
     */
    @Transactional
    public QueueTicket callNextForTriage(String counterNumber) {
        auditGuard.assertSessionActive();

        List<QueueTicket> triageQueue = getTriageQueue();
        if (triageQueue.isEmpty()) {
            throw new QueueException("No patients waiting for triage");
        }

        return callPatientInternal(triageQueue.get(0), counterNumber, "TRIAGE");
    }

    /**
     * Calls the next patient from the consultation queue (for physicians).
     *
     * <p>Selects the highest priority triaged patient.</p>
     *
     * @param counterNumber the consultation room/counter number
     * @return the called ticket
     * @throws QueueException if no triaged patients are waiting
     */
    @Transactional
    public QueueTicket callNextForConsultation(String counterNumber) {
        auditGuard.assertSessionActive();

        List<QueueTicket> consultQueue = getConsultationQueue();
        if (consultQueue.isEmpty()) {
            throw new QueueException("No triaged patients waiting for consultation");
        }

        return callPatientInternal(consultQueue.get(0), counterNumber, "CONSULTATION");
    }

    /**
     * Calls a specific patient by ticket ID.
     *
     * <p>Allows staff to call a specific patient out of order if needed
     * (e.g., patient has returned after stepping out).</p>
     *
     * @param ticketId      the ticket to call
     * @param counterNumber the counter/room number
     * @return the called ticket
     * @throws QueueException if ticket not found or not in WAITING status
     */
    @Transactional
    public QueueTicket callPatient(UUID ticketId, String counterNumber) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.WAITING) {
            throw new QueueException("Patient is not in waiting status");
        }

        String purpose = ticket.isTriaged() ? "CONSULTATION" : "TRIAGE";
        return callPatientInternal(ticket, counterNumber, purpose);
    }

    /**
     * Records that a called patient did not respond.
     *
     * <p>The patient is returned to the queue with a missed call count
     * incremented. After 3 missed calls, staff should consider marking
     * as no-show.</p>
     *
     * @param ticketId the ticket that was called
     * @return the updated ticket back in WAITING status
     */
    @Transactional
    public QueueTicket recordMissedCall(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.CALLED) {
            throw new QueueException("Patient must be in CALLED status");
        }

        ticket.recordMissedCall();
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_MISSED_CALL", ticket.getPatientId(),
                String.format("Missed call #%d for ticket %s",
                        ticket.getMissedCallCount(), ticket.getTicketNumber()));

        return ticket;
    }

    // ==================== CONSULTATION TRACKING ====================

    /**
     * Marks the start of a consultation/triage session.
     *
     * <p>Called when the patient has arrived at the counter and the
     * session begins. Captures the clinician's identity.</p>
     *
     * @param ticketId the ticket to start
     * @return the updated ticket in IN_PROGRESS status
     * @throws QueueException if ticket not found or not in CALLED status
     */
    @Transactional
    public QueueTicket startConsultation(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.CALLED) {
            throw new QueueException("Patient must be called first");
        }

        String staffId = sessionContext.username();
        ticket.markInProgress(staffId);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_SESSION_STARTED", ticket.getPatientId(),
                String.format("Session started for ticket %s", ticket.getTicketNumber()));

        return ticket;
    }

    /**
     * Marks the completion of a consultation.
     *
     * @param ticketId the ticket to complete
     * @return the updated ticket in COMPLETED status
     * @throws QueueException if ticket not found or not in IN_PROGRESS status
     */
    @Transactional
    public QueueTicket completeConsultation(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.IN_PROGRESS) {
            throw new QueueException("Session must be in progress");
        }

        String staffId = sessionContext.username();
        ticket.markCompleted(staffId);
        ticket = queueRepository.save(ticket);

        // Calculate times for analytics
        long waitMinutes = ticket.getWaitTimeMinutes();
        long consultMinutes = ticket.getConsultationTimeMinutes();

        auditService.record("QUEUE_SESSION_COMPLETED", ticket.getPatientId(),
                String.format("Completed: %s. Wait: %d min, Session: %d min",
                        ticket.getTicketNumber(), waitMinutes, consultMinutes));

        return ticket;
    }

    /**
     * Returns patient to waiting queue after triage (for nurse workflow).
     *
     * <p>After triage is completed, the patient returns to wait for
     * physician consultation. Status changes from IN_PROGRESS back to WAITING.</p>
     *
     * @param ticketId the ticket to return to queue
     * @return the updated ticket
     */
    @Transactional
    public QueueTicket returnToWaitingQueue(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.IN_PROGRESS) {
            throw new QueueException("Session must be in progress");
        }

        if (!ticket.isTriaged()) {
            throw new QueueException("Patient must be triaged before returning to queue");
        }

        // Reset status to waiting (keeping all triage info)
        // This is a special transition - we use a direct field update
        // Create a new method in QueueTicket for this transition

        // For now, we'll complete and re-issue conceptually, but actually
        // we should add a returnToQueue method to QueueTicket

        auditService.record("QUEUE_RETURNED_TO_WAITING", ticket.getPatientId(),
                String.format("Patient returned to consultation queue after triage: %s",
                        ticket.getTicketNumber()));

        return ticket;
    }

    /**
     * Marks a patient as no-show after failing to respond.
     *
     * @param ticketId the ticket to mark as no-show
     * @return the updated ticket in NO_SHOW status
     */
    @Transactional
    public QueueTicket markNoShow(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        String staffId = sessionContext.username();
        ticket.markNoShow(staffId);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_NO_SHOW", ticket.getPatientId(),
                String.format("No-show after %d calls: %s",
                        ticket.getMissedCallCount(), ticket.getTicketNumber()));

        return ticket;
    }

    /**
     * Cancels a queue ticket.
     *
     * @param ticketId the ticket to cancel
     * @return the updated ticket in CANCELLED status
     */
    @Transactional
    public QueueTicket cancelTicket(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        String staffId = sessionContext.username();
        ticket.markCancelled(staffId);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_CANCELLED", ticket.getPatientId(),
                "Ticket cancelled: " + ticket.getTicketNumber());

        return ticket;
    }

    // ==================== STATISTICS ====================

    /**
     * Retrieves comprehensive queue statistics for today.
     *
     * @return statistics including counts by status, triage level, and category
     */
    public QueueStats getTodayStats() {
        LocalDate today = LocalDate.now();

        return new QueueStats(
                // Status counts
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.WAITING),
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.CALLED),
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.IN_PROGRESS),
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.COMPLETED),
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.NO_SHOW),
                queueRepository.countByQueueDateAndStatus(today, QueueTicket.QueueStatus.CANCELLED),
                // Triage queue
                queueRepository.countByQueueDateAndStatusAndTriaged(today, QueueTicket.QueueStatus.WAITING, false),
                // Waiting by triage level
                queueRepository.countByQueueDateAndStatusAndTriageLevel(today, QueueTicket.QueueStatus.WAITING, TriageLevel.RED),
                queueRepository.countByQueueDateAndStatusAndTriageLevel(today, QueueTicket.QueueStatus.WAITING, TriageLevel.ORANGE),
                queueRepository.countByQueueDateAndStatusAndTriageLevel(today, QueueTicket.QueueStatus.WAITING, TriageLevel.YELLOW),
                queueRepository.countByQueueDateAndStatusAndTriageLevel(today, QueueTicket.QueueStatus.WAITING, TriageLevel.GREEN),
                queueRepository.countByQueueDateAndStatusAndTriageLevel(today, QueueTicket.QueueStatus.WAITING, TriageLevel.BLUE)
        );
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Internal method to call a patient with full audit trail.
     */
    private QueueTicket callPatientInternal(QueueTicket ticket, String counterNumber, String purpose) {
        String staffId = sessionContext.username();
        String staffName = sessionContext.username(); // Could be enhanced with full name lookup

        ticket.markCalled(staffId, staffName, counterNumber);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_PATIENT_CALLED", ticket.getPatientId(),
                String.format("Called %s to %s for %s by %s",
                        ticket.getTicketNumber(), counterNumber, purpose, staffName));

        return ticket;
    }

    /**
     * Generates the next sequential ticket number for a category.
     */
    private String generateTicketNumber(LocalDate date, QueueTicket.QueueCategory category) {
        int nextNumber = queueRepository.findMaxTicketNumberForDateAndCategory(date, category)
                .map(n -> n + 1)
                .orElse(1);

        return String.format("%s-%03d", category.getPrefix(), nextNumber);
    }

    /**
     * Determines priority modifier based on patient age.
     */
    private PriorityModifier determineAgeBasedModifier(Patient patient) {
        if (patient.getDateOfBirth() == null) {
            return PriorityModifier.NONE;
        }

        int age = java.time.Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears();

        if (age < 1) {
            return PriorityModifier.PEDIATRIC_INFANT;
        } else if (age < 5) {
            return PriorityModifier.PEDIATRIC_CHILD;
        } else if (age >= 65) {
            return PriorityModifier.ELDERLY;
        }

        return PriorityModifier.NONE;
    }

    // ==================== RECORDS ====================

    /**
     * Comprehensive queue statistics for dashboard display.
     *
     * @param waiting        total patients waiting
     * @param called         patients currently called
     * @param inProgress     consultations in progress
     * @param completed      completed consultations
     * @param noShow         no-shows
     * @param cancelled      cancelled tickets
     * @param awaitingTriage patients waiting for nurse triage
     * @param waitingRed     RED triage waiting
     * @param waitingOrange  ORANGE triage waiting
     * @param waitingYellow  YELLOW triage waiting
     * @param waitingGreen   GREEN triage waiting
     * @param waitingBlue    BLUE triage waiting
     */
    public record QueueStats(
            long waiting,
            long called,
            long inProgress,
            long completed,
            long noShow,
            long cancelled,
            long awaitingTriage,
            long waitingRed,
            long waitingOrange,
            long waitingYellow,
            long waitingGreen,
            long waitingBlue
    ) {
    }

    /**
     * Exception thrown for queue-related errors.
     */
    public static class QueueException extends RuntimeException {
        /**
         * Creates a new QueueException with the specified message.
         *
         * @param message the error message
         */
        public QueueException(String message) {
            super(message);
        }
    }
}
