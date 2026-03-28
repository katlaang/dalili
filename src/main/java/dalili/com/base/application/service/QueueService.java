package dalili.com.base.application.service;


import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.PriorityModifier;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.queue.QueueTicketCounter;
import dalili.com.base.domain.triage.TriageLevel;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketCounterRepository;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

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

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueTicketRepository queueRepository;
    private final QueueTicketCounterRepository queueTicketCounterRepository;
    private final EncounterRepository encounterRepository;
    private final PatientService patientService;
    private final PatientDataAccessService patientDataAccessService;
    private final FacilityWorkflowConfigService facilityWorkflowConfigService;
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
            QueueTicketCounterRepository queueTicketCounterRepository,
            EncounterRepository encounterRepository,
            PatientService patientService,
            PatientDataAccessService patientDataAccessService,
            FacilityWorkflowConfigService facilityWorkflowConfigService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.queueRepository = queueRepository;
        this.queueTicketCounterRepository = queueTicketCounterRepository;
        this.encounterRepository = encounterRepository;
        this.patientService = patientService;
        this.patientDataAccessService = patientDataAccessService;
        this.facilityWorkflowConfigService = facilityWorkflowConfigService;
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
        if (category == QueueTicket.QueueCategory.EMERGENCY) {
            facilityWorkflowConfigService.assertEmergencyFlowEnabled();
        }
        if (category == QueueTicket.QueueCategory.FOLLOW_UP) {
            facilityWorkflowConfigService.assertAppointmentFlowEnabled();
        }

        LocalDate today = LocalDate.now();
        String sanitizedComplaint = initialComplaint;
        if (sessionContext.role() == Role.RECEPTIONIST) {
            sanitizedComplaint = null;
            log.info("Queue complaint stripped for privacy patientId={} role={}", patientId, sessionContext.role());
        }

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
                sanitizedComplaint,
                issuedBy
        );
        ticket.capturePatientSnapshot(patient.getMrn(), patient.getFullName(), patient.getDateOfBirth());

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
        facilityWorkflowConfigService.assertEmergencyFlowEnabled();

        LocalDate today = LocalDate.now();
        String ticketNumber = generateTicketNumber(today, QueueTicket.QueueCategory.EMERGENCY);
        String issuedBy = sessionContext.username();

        QueueTicket ticket = QueueTicket.createEmergency(
                patientId,
                ticketNumber,
                initialComplaint,
                issuedBy
        );
        Patient patient = patientService.findById(patientId);
        ticket.capturePatientSnapshot(patient.getMrn(), patient.getFullName(), patient.getDateOfBirth());

        ticket = queueRepository.save(ticket);
        log.info("Emergency queue ticket issued ticketId={} patientId={}", ticket.getId(), patientId);

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
        refreshAppointmentPriorityBoosts();
        return queueRepository.findByQueueDateAndStatusAndTriagedOrderByEffectivePriorityAscCreatedAtAsc(
                LocalDate.now(), QueueTicket.QueueStatus.WAITING, true
                ).stream()
                .filter(ticket -> !ticket.isAncillaryHold())
                .toList();
    }

    /**
     * Retrieves the full waiting queue ordered by priority.
     *
     * <p>Returns all WAITING tickets regardless of triage status.</p>
     *
     * @return list of waiting tickets in priority order
     */
    public List<QueueTicket> getWaitingQueue() {
        refreshAppointmentPriorityBoosts();
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
        refreshAppointmentPriorityBoosts();
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
        refreshAppointmentPriorityBoosts();
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

        UUID clinicianUserId = sessionContext.userId();
        String clinicianEmployeeId = sessionContext.username();
        String clinicianName = sessionContext.username();

        QueueTicket selected = selectEmergencyPriorityTicket(consultQueue)
                .orElseGet(() -> selectPriorityAppointmentTicket(consultQueue, clinicianUserId, clinicianEmployeeId)
                        .orElseGet(() -> selectAssignedTicket(consultQueue, clinicianUserId, clinicianEmployeeId)
                                .orElseGet(() -> selectRepeatPatientTicket(consultQueue, clinicianUserId, clinicianEmployeeId)
                                        .orElseGet(() -> consultQueue.stream()
                                                .filter(ticket -> !isAssignedToAnotherClinician(ticket, clinicianUserId, clinicianEmployeeId))
                                                .findFirst()
                                                .orElse(consultQueue.get(0))))));

        boolean autoAssignRepeat = !selected.isAssignedToClinician(clinicianUserId, clinicianEmployeeId)
                && !isAssignedToAnotherClinician(selected, clinicianUserId, clinicianEmployeeId)
                && clinicianUserId != null
                && clinicianEmployeeId != null
                && clinicianName != null
                && hasCompletedEncounterWithClinician(selected.getPatientId(), clinicianUserId);

        if (autoAssignRepeat) {
            selected.assignClinician(
                    clinicianName,
                    clinicianEmployeeId,
                    clinicianUserId,
                    clinicianEmployeeId,
                    clinicianName,
                    "AUTO_REPEAT_MATCH",
                    "Auto-assigned to repeat clinician match"
            );
            selected = queueRepository.save(selected);

            auditService.record("QUEUE_AUTO_REPEAT_ASSIGNMENT", selected.getPatientId(),
                    String.format("Ticket %s auto-assigned to repeat clinician %s (%s)",
                            selected.getTicketNumber(), clinicianName, clinicianEmployeeId));
        }

        return callPatientInternal(selected, counterNumber, "CONSULTATION");
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
        if (ticket.isAncillaryHold()) {
            throw new QueueException("Patient is on ancillary hold and cannot be called for consultation yet");
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

        if (ticket.getStatus() != QueueTicket.QueueStatus.IN_PROGRESS
                && ticket.getStatus() != QueueTicket.QueueStatus.CALLED) {
            throw new QueueException("Ticket must be CALLED or IN_PROGRESS");
        }

        if (!ticket.isTriaged()) {
            throw new QueueException("Patient must be triaged before returning to queue");
        }

        ticket.returnToWaitingAfterTriage();
        ticket = queueRepository.save(ticket);
        log.info(
                "Queue ticket returned to waiting ticketId={} patientId={} status={}",
                ticket.getId(),
                ticket.getPatientId(),
                ticket.getStatus()
        );

        auditService.record("QUEUE_RETURNED_TO_WAITING", ticket.getPatientId(),
                String.format("Patient returned to consultation queue after triage: %s",
                        ticket.getTicketNumber()));

        return ticket;
    }

    /**
     * Places a triaged patient on ancillary hold while waiting for follow-up work such as pregnancy testing.
     */
    @Transactional
    public QueueTicket holdForAncillaryStep(UUID ticketId, String reason) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (!ticket.isTriaged()) {
            throw new QueueException("Patient must be triaged before being placed on ancillary hold");
        }
        if (reason == null || reason.isBlank()) {
            throw new QueueException("Ancillary hold reason is required");
        }

        ticket.placeAncillaryHold(sessionContext.username(), reason);
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_ANCILLARY_HOLD", ticket.getPatientId(),
                String.format("Ticket %s placed on ancillary hold: %s", ticket.getTicketNumber(), reason.trim()));
        return ticket;
    }

    /**
     * Clears ancillary hold and returns patient to the consultation waiting pool.
     */
    @Transactional
    public QueueTicket releaseAncillaryHold(UUID ticketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (!ticket.isAncillaryHold()) {
            throw new QueueException("Ticket is not on ancillary hold");
        }

        ticket.clearAncillaryHold();
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_ANCILLARY_HOLD_RELEASED", ticket.getPatientId(),
                String.format("Ticket %s released from ancillary hold", ticket.getTicketNumber()));
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
     * Captures triage nurse handoff to a specific clinician for consultation.
     *
     * @param ticketId the queue ticket UUID
     * @param input    clinician handoff details
     * @return updated ticket with clinician assignment metadata
     */
    @Transactional
    public QueueTicket handoffToClinician(UUID ticketId, ClinicianHandoffInput input) {
        auditGuard.assertSessionActive();

        if (input == null) {
            throw new QueueException("Clinician handoff details are required");
        }

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (!ticket.isTriaged()) {
            throw new QueueException("Patient must be triaged before clinician handoff");
        }
        if (ticket.getStatus() == QueueTicket.QueueStatus.COMPLETED
                || ticket.getStatus() == QueueTicket.QueueStatus.CANCELLED
                || ticket.getStatus() == QueueTicket.QueueStatus.NO_SHOW
                || ticket.getStatus() == QueueTicket.QueueStatus.ADMITTED) {
            throw new QueueException("Cannot handoff clinician for closed ticket status: " + ticket.getStatus());
        }

        String assignedByStaffId = sessionContext.username();
        String assignedByStaffName = sessionContext.username();

        ticket.assignClinician(
                input.clinicianName(),
                input.clinicianEmployeeId(),
                input.clinicianUserId(),
                assignedByStaffId,
                assignedByStaffName,
                "NURSE_HANDOFF",
                input.handoffNotes()
        );

        ticket = queueRepository.save(ticket);
        log.info("Queue handoff recorded ticketId={} patientId={} clinicianEmployeeId={}",
                ticket.getId(), ticket.getPatientId(), ticket.getAssignedClinicianEmployeeId());

        auditService.record("QUEUE_CLINICIAN_HANDOFF", ticket.getPatientId(),
                String.format("Ticket %s assigned to clinician %s (%s) by %s",
                        ticket.getTicketNumber(),
                        ticket.getAssignedClinicianName(),
                        ticket.getAssignedClinicianEmployeeId(),
                        assignedByStaffId));

        return ticket;
    }

    /**
     * Marks a patient as admitted from queue workflow.
     *
     * @param ticketId the ticket to mark admitted
     * @param reason   optional admission reason
     * @return the updated ticket in ADMITTED status
     */
    @Transactional
    public QueueTicket admitPatient(UUID ticketId, String reason) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(ticketId)
                .orElseThrow(() -> new QueueException("Ticket not found"));

        if (ticket.getStatus() != QueueTicket.QueueStatus.IN_PROGRESS
                && ticket.getStatus() != QueueTicket.QueueStatus.CALLED) {
            throw new QueueException("Patient must be CALLED or IN_PROGRESS to admit");
        }

        String staffId = sessionContext.username();
        ticket.markAdmitted(staffId, reason);
        ticket = queueRepository.save(ticket);
        patientDataAccessService.recordAdmissionGrant(ticket.getPatientId(), ticket.getId());
        log.info("Queue admission recorded ticketId={} patientId={}", ticket.getId(), ticket.getPatientId());

        auditService.record("QUEUE_ADMITTED", ticket.getPatientId(),
                String.format("Patient admitted from queue: %s", ticket.getTicketNumber()));

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

        if ("CONSULTATION".equals(purpose)) {
            ticket.markCalledForConsultation(sessionContext.userId(), staffId, staffName, counterNumber);
        } else {
            ticket.markCalled(staffId, staffName, counterNumber);
        }
        ticket = queueRepository.save(ticket);

        auditService.record("QUEUE_PATIENT_CALLED", ticket.getPatientId(),
                String.format("Called %s to %s for %s by %s",
                        ticket.getTicketNumber(), counterNumber, purpose, staffName));

        return ticket;
    }

    /**
     * Recomputes appointment-time priority boosts for waiting tickets.
     *
     * <p>Appointment patients receive a temporary queue boost near their scheduled
     * time while preserving triage-first ordering.</p>
     *
     * @return number of tickets whose effective priority was updated
     */
    @Transactional
    public int refreshAppointmentPriorityBoosts() {
        if (!facilityWorkflowConfigService.isAppointmentFlowEnabled()
                || !facilityWorkflowConfigService.isAppointmentPriorityBoostEnabled()) {
            return 0;
        }

        int beforeMinutes = facilityWorkflowConfigService.getAppointmentPriorityBoostMinutesBefore();
        int afterMinutes = facilityWorkflowConfigService.getAppointmentPriorityBoostMinutesAfter();
        Instant now = Instant.now();

        List<QueueTicket> waitingTickets = queueRepository.findByQueueDateAndStatusOrderByEffectivePriorityAscCreatedAtAsc(
                LocalDate.now(),
                QueueTicket.QueueStatus.WAITING
        );

        int changed = 0;
        for (QueueTicket ticket : waitingTickets) {
            if (!ticket.hasLinkedAppointment()) {
                continue;
            }

            boolean shouldBoost = ticket.isWithinAppointmentPriorityWindow(now, beforeMinutes, afterMinutes);
            boolean stateChanged = shouldBoost
                    ? ticket.applyAppointmentPriorityBoost()
                    : ticket.clearAppointmentPriorityBoost();

            if (stateChanged) {
                queueRepository.save(ticket);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Appointment priority boosts updated count={}", changed);
        }
        return changed;
    }

    private Optional<QueueTicket> selectAssignedTicket(
            List<QueueTicket> consultQueue,
            UUID clinicianUserId,
            String clinicianEmployeeId
    ) {
        return consultQueue.stream()
                .filter(ticket -> ticket.isAssignedToClinician(clinicianUserId, clinicianEmployeeId))
                .findFirst();
    }

    private Optional<QueueTicket> selectEmergencyPriorityTicket(List<QueueTicket> consultQueue) {
        return consultQueue.stream()
                .filter(ticket -> ticket.getTriageLevel() == TriageLevel.RED || ticket.getTriageLevel() == TriageLevel.ORANGE)
                .findFirst();
    }

    private Optional<QueueTicket> selectPriorityAppointmentTicket(
            List<QueueTicket> consultQueue,
            UUID clinicianUserId,
            String clinicianEmployeeId
    ) {
        boolean emergencyWaiting = consultQueue.stream()
                .anyMatch(ticket -> ticket.getTriageLevel() == TriageLevel.RED || ticket.getTriageLevel() == TriageLevel.ORANGE);
        if (emergencyWaiting) {
            return Optional.empty();
        }

        return consultQueue.stream()
                .filter(QueueTicket::hasLinkedAppointment)
                .filter(ticket -> !isAssignedToAnotherClinician(ticket, clinicianUserId, clinicianEmployeeId))
                .findFirst();
    }

    private Optional<QueueTicket> selectRepeatPatientTicket(
            List<QueueTicket> consultQueue,
            UUID clinicianUserId,
            String clinicianEmployeeId
    ) {
        if (clinicianUserId == null) {
            return Optional.empty();
        }

        List<UUID> patientIds = consultQueue.stream()
                .map(QueueTicket::getPatientId)
                .distinct()
                .toList();

        if (patientIds.isEmpty()) {
            return Optional.empty();
        }

        Set<UUID> repeatPatientIds = new HashSet<>(
                encounterRepository.findDistinctCompletedPatientIdsByClinicianIn(clinicianUserId, patientIds)
        );

        if (repeatPatientIds.isEmpty()) {
            return Optional.empty();
        }

        return consultQueue.stream()
                .filter(ticket -> repeatPatientIds.contains(ticket.getPatientId()))
                .filter(ticket -> !isAssignedToAnotherClinician(ticket, clinicianUserId, clinicianEmployeeId))
                .findFirst();
    }

    private boolean isAssignedToAnotherClinician(
            QueueTicket ticket,
            UUID clinicianUserId,
            String clinicianEmployeeId
    ) {
        boolean hasAssignment = ticket.getAssignedClinicianUserId() != null
                || (ticket.getAssignedClinicianEmployeeId() != null
                && !ticket.getAssignedClinicianEmployeeId().isBlank());

        return hasAssignment && !ticket.isAssignedToClinician(clinicianUserId, clinicianEmployeeId);
    }

    private boolean hasCompletedEncounterWithClinician(UUID patientId, UUID clinicianUserId) {
        if (clinicianUserId == null) {
            return false;
        }
        return encounterRepository.findDistinctCompletedPatientIdsByClinicianIn(
                        clinicianUserId,
                        List.of(patientId)
                )
                .stream()
                .anyMatch(patientId::equals);
    }

    /**
     * Generates the next sequential ticket number for a category.
     */
    private String generateTicketNumber(LocalDate date, QueueTicket.QueueCategory category) {
        int nextNumber = reserveNextTicketSequence(date, category);

        return String.format("%s-%03d", category.getPrefix(), nextNumber);
    }

    private int reserveNextTicketSequence(LocalDate date, QueueTicket.QueueCategory category) {
        QueueTicketCounter counter = queueTicketCounterRepository
                .findByQueueDateAndCategoryForUpdate(date, category)
                .orElseGet(() -> createCounter(date, category));

        int nextNumber = counter.reserveNextSequence();
        queueTicketCounterRepository.save(counter);
        return nextNumber;
    }

    private QueueTicketCounter createCounter(LocalDate date, QueueTicket.QueueCategory category) {
        try {
            return queueTicketCounterRepository.saveAndFlush(QueueTicketCounter.initialize(date, category));
        } catch (DataIntegrityViolationException conflict) {
            return queueTicketCounterRepository.findByQueueDateAndCategoryForUpdate(date, category)
                    .orElseThrow(() -> new QueueException("Unable to reserve queue number. Please retry."));
        }
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

    public record ClinicianHandoffInput(
            String clinicianName,
            String clinicianEmployeeId,
            UUID clinicianUserId,
            String handoffNotes
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
