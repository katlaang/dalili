package dalili.com.base.domain.queue;

import dalili.com.base.domain.triage.TriageLevel;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Represents a patient's position in the clinic queue.
 *
 * <p>Each queue ticket tracks a patient's journey through the clinic:
 * check-in → triage → waiting → called → consultation → completed.</p>
 *
 * <p>Queue tickets are date-specific and reset daily. Ticket numbers
 * are formatted as {CATEGORY_PREFIX}-{SEQUENCE} (e.g., "A-001" for general,
 * "E-001" for emergency).</p>
 *
 * <p>Priority is determined by:
 * <ol>
 *   <li>Triage level (RED highest, BLUE lowest) - set after nurse triage</li>
 *   <li>Priority modifiers (pediatric, elderly, ambulance, etc.)</li>
 *   <li>Check-in time (FIFO within same priority)</li>
 * </ol>
 * </p>
 *
 * <p>Clinical details (vitals, observations) are stored separately in
 * {@link dalili.com.base.domain.triage.TriageAssessment}.</p>
 *
 * @see TriageLevel
 * @see PriorityModifier
 * @see dalili.com.base.domain.triage.TriageAssessment
 */
@Entity
@Table(name = "queue_tickets", indexes = {
        @Index(name = "idx_queue_date_number", columnList = "queueDate, ticketNumber"),
        @Index(name = "idx_queue_patient", columnList = "patientId"),
        @Index(name = "idx_queue_status", columnList = "status"),
        @Index(name = "idx_queue_triage", columnList = "triageLevel"),
        @Index(name = "idx_queue_priority", columnList = "queueDate, status, effectivePriority, createdAt")
})
@Getter
public class QueueTicket {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Reference to the patient record.
     */
    @Column(nullable = false)
    private UUID patientId;

    /**
     * The date this ticket is valid for. Resets daily.
     */
    @Column(nullable = false)
    private LocalDate queueDate;

    /**
     * Human-readable ticket number (e.g., "WK-001", "PR-015").
     */
    @Column(nullable = false)
    private String ticketNumber;

    /**
     * Department or service category.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueCategory category;

    /**
     * Clinical triage level based on MTS.
     * Initially GREEN (default), updated after nurse triage assessment.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriageLevel triageLevel;

    /**
     * Additional priority modifier (pediatric, elderly, ambulance, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriorityModifier priorityModifier;

    /**
     * Calculated effective priority for queue ordering.
     * Lower number = higher priority.
     */
    @Column(nullable = false)
    private int effectivePriority;

    /**
     * Current status in the queue workflow.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueStatus status;

    /**
     * Brief description of presenting complaint (from kiosk or reception).
     */
    @Column(length = 500)
    private String initialComplaint;

    /**
     * Snapshot of patient MRN captured at ticket issuance.
     */
    @Column(length = 120)
    private String patientMrnSnapshot;

    /**
     * Snapshot of patient full name captured at ticket issuance.
     */
    @Column(length = 255)
    private String patientNameSnapshot;

    /**
     * Snapshot of patient DOB captured at ticket issuance.
     */
    @Column
    private LocalDate patientDateOfBirthSnapshot;

    /**
     * Flag indicating triage assessment has been completed.
     */
    @Column(nullable = false)
    private boolean triaged = false;

    /**
     * Reference to the triage assessment (set after triage completed).
     */
    @Column
    private UUID triageAssessmentId;

    /**
     * Computed triage summary captured for queue visibility.
     */
    @Column(length = 2000)
    private String triageSummary;

    /**
     * Most likely diagnosis suggestion from AI triage outcome.
     */
    @Column(length = 500)
    private String suggestedPrimaryDiagnosis;

    /**
     * Flattened suggested diagnoses from AI triage outcome.
     */
    @Column(length = 2000)
    private String suggestedDiagnoses;

    /**
     * User UUID of the clinician assigned for consultation handoff.
     */
    @Column
    private UUID assignedClinicianUserId;

    /**
     * Display name of the clinician assigned for consultation.
     */
    @Column(length = 255)
    private String assignedClinicianName;

    /**
     * Employee/staff ID of the clinician assigned for consultation.
     */
    @Column(length = 120)
    private String assignedClinicianEmployeeId;

    /**
     * Assignment source (for example: NURSE_HANDOFF, AUTO_REPEAT_MATCH, DIRECT_CALL).
     */
    @Column(length = 40)
    private String clinicianAssignmentSource;

    /**
     * Optional nurse handoff notes for the receiving clinician.
     */
    @Column(length = 500)
    private String clinicianHandoffNotes;

    /**
     * Timestamp when clinician assignment was captured.
     */
    @Column
    private Instant clinicianAssignedAt;

    /**
     * Staff ID of the person who assigned the clinician.
     */
    @Column(length = 120)
    private String clinicianAssignedByStaffId;

    /**
     * Name of the person who assigned the clinician.
     */
    @Column(length = 255)
    private String clinicianAssignedByStaffName;

    /**
     * Timestamp when assigned clinician accepted handoff by calling patient.
     */
    @Column
    private Instant clinicianHandoffAcceptedAt;

    /**
     * Staff ID of clinician who accepted handoff.
     */
    @Column(length = 120)
    private String clinicianHandoffAcceptedByStaffId;

    /**
     * Display name of clinician who accepted handoff.
     */
    @Column(length = 255)
    private String clinicianHandoffAcceptedByStaffName;

    // ==================== TIMESTAMPS ====================

    /**
     * Timestamp when ticket was issued (check-in time).
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Staff/device ID who issued the ticket.
     */
    @Column(nullable = false)
    private String issuedBy;

    /**
     * Timestamp when triage was completed.
     */
    @Column
    private Instant triagedAt;

    /**
     * Timestamp when patient was called.
     */
    @Column
    private Instant calledAt;

    /**
     * Staff ID who called the patient.
     */
    @Column
    private String calledByStaffId;

    /**
     * Staff name who called the patient (for display/announcement).
     */
    @Column
    private String calledByStaffName;

    /**
     * Counter/room number where patient should go.
     */
    @Column
    private String counterNumber;

    /**
     * Timestamp when consultation started.
     */
    @Column
    private Instant startedAt;

    /**
     * Staff ID who started the consultation.
     */
    @Column
    private String startedByStaffId;

    /**
     * Timestamp when consultation completed or ticket closed.
     */
    @Column
    private Instant completedAt;

    /**
     * Staff ID who completed/closed the ticket.
     */
    @Column
    private String completedByStaffId;

    /**
     * Timestamp of last priority escalation.
     */
    @Column
    private Instant lastEscalatedAt;

    /**
     * Staff ID who last escalated the priority.
     */
    @Column
    private String escalatedByStaffId;

    /**
     * Reason for priority escalation (if any).
     */
    @Column(length = 500)
    private String escalationReason;

    /**
     * Reason for admission decision (if admitted from queue workflow).
     */
    @Column(length = 500)
    private String admissionReason;

    /**
     * Linked appointment UUID (for appointment check-in flow).
     */
    @Column
    private UUID appointmentId;

    /**
     * Scheduled appointment timestamp.
     */
    @Column
    private Instant appointmentScheduledAt;

    /**
     * Appointment check-in window open timestamp.
     */
    @Column
    private Instant appointmentWindowOpensAt;

    /**
     * Appointment check-in window close timestamp.
     */
    @Column
    private Instant appointmentWindowClosesAt;

    /**
     * Whether appointment-time priority boost is currently active.
     */
    @Column(nullable = false)
    private boolean appointmentPriorityBoostApplied = false;

    /**
     * Timestamp when appointment-time priority boost was applied.
     */
    @Column
    private Instant appointmentPriorityBoostAppliedAt;

    // ==================== FLAGS ====================

    /**
     * Flag indicating patient arrived by ambulance.
     */
    @Column(nullable = false)
    private boolean ambulanceArrival = false;

    /**
     * Number of times patient was called but didn't respond.
     */
    @Column(nullable = false)
    private int missedCallCount = 0;

    protected QueueTicket() {
    }

    /**
     * Creates a new queue ticket at check-in (before triage).
     *
     * <p>Initial triage level is GREEN (standard). This will be updated
     * after the nurse performs the triage assessment.</p>
     *
     * @param patientId        the patient's UUID
     * @param ticketNumber     the formatted ticket number (e.g., "WK-001")
     * @param category         the queue category
     * @param priorityModifier any initial priority modifiers (e.g., PEDIATRIC based on age)
     * @param initialComplaint brief complaint from kiosk/reception
     * @param issuedBy         staff/device ID issuing the ticket
     * @return a new QueueTicket in WAITING status with GREEN triage
     */
    public static QueueTicket create(
            UUID patientId,
            String ticketNumber,
            QueueCategory category,
            PriorityModifier priorityModifier,
            String initialComplaint,
            String issuedBy
    ) {
        QueueTicket ticket = new QueueTicket();
        ticket.patientId = patientId;
        ticket.queueDate = LocalDate.now();
        ticket.ticketNumber = ticketNumber;
        ticket.category = category;
        ticket.triageLevel = TriageLevel.GREEN; // Default until triaged
        ticket.priorityModifier = priorityModifier;
        ticket.initialComplaint = initialComplaint;
        ticket.issuedBy = issuedBy;
        ticket.status = QueueStatus.WAITING;
        ticket.createdAt = Instant.now();
        ticket.effectivePriority = calculateEffectivePriority(TriageLevel.GREEN, priorityModifier, false);
        return ticket;
    }

    /**
     * Creates an emergency queue ticket for ambulance arrivals.
     *
     * <p>Emergency tickets start at ORANGE triage level and have
     * AMBULANCE_ARRIVAL priority modifier. They will still go through
     * formal triage but are prioritized immediately.</p>
     *
     * @param patientId        the patient's UUID
     * @param ticketNumber     the formatted ticket number
     * @param initialComplaint brief complaint from paramedics
     * @param issuedBy         staff ID issuing the ticket
     * @return a new emergency QueueTicket
     */
    public static QueueTicket createEmergency(
            UUID patientId,
            String ticketNumber,
            String initialComplaint,
            String issuedBy
    ) {
        QueueTicket ticket = new QueueTicket();
        ticket.patientId = patientId;
        ticket.queueDate = LocalDate.now();
        ticket.ticketNumber = ticketNumber;
        ticket.category = QueueCategory.EMERGENCY;
        ticket.triageLevel = TriageLevel.ORANGE; // Emergency default
        ticket.priorityModifier = PriorityModifier.AMBULANCE_ARRIVAL;
        ticket.initialComplaint = initialComplaint;
        ticket.issuedBy = issuedBy;
        ticket.status = QueueStatus.WAITING;
        ticket.createdAt = Instant.now();
        ticket.ambulanceArrival = true;
        ticket.effectivePriority = calculateEffectivePriority(
                TriageLevel.ORANGE,
                PriorityModifier.AMBULANCE_ARRIVAL,
                false
        );
        return ticket;
    }

    public void capturePatientSnapshot(String mrn, String fullName, LocalDate dateOfBirth) {
        this.patientMrnSnapshot = sanitizeText(mrn, 120);
        this.patientNameSnapshot = sanitizeText(fullName, 255);
        this.patientDateOfBirthSnapshot = dateOfBirth;
    }

    /**
     * Date-stamped reference that disambiguates repeating daily queue numbers.
     *
     * <p>Example: 20260314-WK-001.</p>
     */
    public String getTrackingNumber() {
        if (queueDate == null || ticketNumber == null || ticketNumber.isBlank()) {
            return ticketNumber;
        }
        return queueDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + ticketNumber;
    }

    // ==================== TRIAGE UPDATES ====================

    /**
     * Calculates the effective priority for queue ordering.
     * Lower value = higher priority.
     *
     * @param triage   the triage level
     * @param modifier the priority modifier
     * @return effective priority value
     */
    private static int calculateEffectivePriority(TriageLevel triage, PriorityModifier modifier) {
        // Base priority from triage level (1-5) * 10
        int basePriority = triage.getPriority() * 10;
        // Subtract modifier boost (higher boost = lower effective priority = seen sooner)
        return basePriority - modifier.getPriorityBoost();
    }

    /**
     * Calculates effective priority including appointment-time boost.
     */
    private static int calculateEffectivePriority(
            TriageLevel triage,
            PriorityModifier modifier,
            boolean appointmentBoostApplied
    ) {
        int appointmentBoost = appointmentBoostApplied ? 2 : 0;
        return calculateEffectivePriority(triage, modifier) - appointmentBoost;
    }

    private static String sanitizeDiagnosisList(List<String> values, int maxLen) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(value.trim());
        }

        if (builder.isEmpty()) {
            return null;
        }
        return sanitizeText(builder.toString(), maxLen);
    }

    private static String sanitizeText(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    /**
     * Updates the triage level after nurse assessment.
     *
     * <p>Called by TriageService after completing the triage assessment.
     * Updates the queue priority accordingly.</p>
     *
     * @param newTriageLevel     the assessed triage level
     * @param triageAssessmentId reference to the triage assessment record
     */
    public void completeTriage(TriageLevel newTriageLevel, UUID triageAssessmentId) {
        this.triageLevel = newTriageLevel;
        this.triageAssessmentId = triageAssessmentId;
        this.triaged = true;
        this.triagedAt = Instant.now();
        this.effectivePriority = calculateEffectivePriority(newTriageLevel, this.priorityModifier, this.appointmentPriorityBoostApplied);
    }

    /**
     * Links this queue ticket to an appointment check-in context.
     */
    public void linkToAppointment(
            UUID appointmentId,
            Instant scheduledAt,
            Instant windowOpensAt,
            Instant windowClosesAt
    ) {
        if (appointmentId == null) {
            throw new IllegalArgumentException("appointmentId is required");
        }
        if (scheduledAt == null || windowOpensAt == null || windowClosesAt == null) {
            throw new IllegalArgumentException("Appointment schedule timestamps are required");
        }
        this.appointmentId = appointmentId;
        this.appointmentScheduledAt = scheduledAt;
        this.appointmentWindowOpensAt = windowOpensAt;
        this.appointmentWindowClosesAt = windowClosesAt;
    }

    /**
     * Attaches triage outcome details for direct queue visibility.
     *
     * @param triageSummary             short summary of triage findings
     * @param suggestedPrimaryDiagnosis top suggested diagnosis, if available
     * @param suggestedDiagnoses        suggested diagnosis list, if available
     */
    public void attachTriageOutcome(
            String triageSummary,
            String suggestedPrimaryDiagnosis,
            List<String> suggestedDiagnoses
    ) {
        this.triageSummary = sanitizeText(triageSummary, 2000);
        this.suggestedPrimaryDiagnosis = sanitizeText(suggestedPrimaryDiagnosis, 500);
        this.suggestedDiagnoses = sanitizeDiagnosisList(suggestedDiagnoses, 2000);
    }

    /**
     * Captures triage-to-clinician assignment details before consultation.
     *
     * @param clinicianName       receiving clinician name
     * @param clinicianEmployeeId receiving clinician employee ID
     * @param clinicianUserId     receiving clinician user UUID (if known)
     * @param assignedByStaffId   handoff initiator staff ID
     * @param assignedByStaffName handoff initiator display name
     * @param assignmentSource    assignment source marker
     * @param handoffNotes        optional handoff notes
     */
    public void assignClinician(
            String clinicianName,
            String clinicianEmployeeId,
            UUID clinicianUserId,
            String assignedByStaffId,
            String assignedByStaffName,
            String assignmentSource,
            String handoffNotes
    ) {
        String normalizedClinicianName = sanitizeText(clinicianName, 255);
        if (normalizedClinicianName == null) {
            throw new IllegalArgumentException("Assigned clinician name is required");
        }

        String normalizedEmployeeId = sanitizeText(clinicianEmployeeId, 120);
        if (normalizedEmployeeId == null) {
            throw new IllegalArgumentException("Assigned clinician employee ID is required");
        }

        String normalizedAssignedById = sanitizeText(assignedByStaffId, 120);
        if (normalizedAssignedById == null) {
            throw new IllegalArgumentException("Assigned-by staff ID is required");
        }

        String normalizedAssignedByName = sanitizeText(assignedByStaffName, 255);
        if (normalizedAssignedByName == null) {
            throw new IllegalArgumentException("Assigned-by staff name is required");
        }

        String normalizedSource = sanitizeText(assignmentSource, 40);
        if (normalizedSource == null) {
            throw new IllegalArgumentException("Assignment source is required");
        }

        this.assignedClinicianName = normalizedClinicianName;
        this.assignedClinicianEmployeeId = normalizedEmployeeId;
        this.assignedClinicianUserId = clinicianUserId;
        this.clinicianAssignedByStaffId = normalizedAssignedById;
        this.clinicianAssignedByStaffName = normalizedAssignedByName;
        this.clinicianAssignmentSource = normalizedSource;
        this.clinicianAssignedAt = Instant.now();
        this.clinicianHandoffNotes = sanitizeText(handoffNotes, 500);
    }

    // ==================== STATUS TRANSITIONS ====================

    /**
     * Returns true if this ticket is assigned to the supplied clinician identity.
     *
     * @param clinicianUserId     current clinician user UUID
     * @param clinicianEmployeeId current clinician employee ID
     * @return true when either user UUID or employee ID matches
     */
    public boolean isAssignedToClinician(UUID clinicianUserId, String clinicianEmployeeId) {
        if (this.assignedClinicianUserId != null && clinicianUserId != null
                && this.assignedClinicianUserId.equals(clinicianUserId)) {
            return true;
        }
        if (this.assignedClinicianEmployeeId == null || clinicianEmployeeId == null) {
            return false;
        }
        return this.assignedClinicianEmployeeId.equalsIgnoreCase(clinicianEmployeeId.trim());
    }

    /**
     * Marks a clinician handoff as accepted and transitions ticket to CALLED state.
     *
     * @param clinicianUserId clinician user UUID
     * @param staffId         clinician employee/staff ID
     * @param staffName       clinician display name
     * @param counterNumber   consultation room/counter
     */
    public void markCalledForConsultation(
            UUID clinicianUserId,
            String staffId,
            String staffName,
            String counterNumber
    ) {
        if (this.assignedClinicianEmployeeId == null && this.assignedClinicianUserId == null) {
            assignClinician(
                    staffName != null && !staffName.isBlank() ? staffName : staffId,
                    staffId,
                    clinicianUserId,
                    staffId,
                    staffName != null && !staffName.isBlank() ? staffName : staffId,
                    "DIRECT_CALL",
                    null
            );
        }

        this.clinicianHandoffAcceptedAt = Instant.now();
        this.clinicianHandoffAcceptedByStaffId = sanitizeText(staffId, 120);
        this.clinicianHandoffAcceptedByStaffName = sanitizeText(staffName, 255);
        markCalled(staffId, staffName, counterNumber);
    }

    /**
     * Escalates the patient's priority due to condition change.
     *
     * <p>Called when a waiting patient's condition worsens. Updates both
     * triage level and priority modifier.</p>
     *
     * @param newTriageLevel the new (higher priority) triage level
     * @param reason         clinical reason for escalation
     * @param staffId        ID of staff performing escalation
     */
    public void escalatePriority(TriageLevel newTriageLevel, String reason, String staffId) {
        this.triageLevel = newTriageLevel;
        this.priorityModifier = PriorityModifier.DETERIORATING;
        this.effectivePriority = calculateEffectivePriority(
                newTriageLevel,
                PriorityModifier.DETERIORATING,
                this.appointmentPriorityBoostApplied
        );
        this.escalationReason = reason;
        this.lastEscalatedAt = Instant.now();
        this.escalatedByStaffId = staffId;
    }

    /**
     * Returns true when this queue ticket is linked to an appointment.
     */
    public boolean hasLinkedAppointment() {
        return this.appointmentId != null && this.appointmentScheduledAt != null;
    }

    /**
     * Returns true if now falls in the appointment priority-boost window.
     */
    public boolean isWithinAppointmentPriorityWindow(Instant now, int minutesBefore, int minutesAfter) {
        if (!hasLinkedAppointment() || now == null) {
            return false;
        }
        Instant windowStart = this.appointmentScheduledAt.minusSeconds(minutesBefore * 60L);
        Instant windowEnd = this.appointmentScheduledAt.plusSeconds(minutesAfter * 60L);
        return !now.isBefore(windowStart) && !now.isAfter(windowEnd);
    }

    /**
     * Marks the patient as called to a counter.
     *
     * @param staffId       ID of staff calling the patient
     * @param staffName     display name of staff (for announcement)
     * @param counterNumber counter/room number
     */
    public void markCalled(String staffId, String staffName, String counterNumber) {
        this.status = QueueStatus.CALLED;
        this.calledAt = Instant.now();
        this.calledByStaffId = staffId;
        this.calledByStaffName = staffName;
        this.counterNumber = counterNumber;
    }

    /**
     * Records that patient didn't respond to call.
     *
     * <p>The patient is returned to the queue with missed call count
     * incremented. After 3 missed calls, staff should consider marking
     * as no-show.</p>
     */
    public void recordMissedCall() {
        this.missedCallCount++;
        this.status = QueueStatus.WAITING;
        this.calledAt = null;
        this.calledByStaffId = null;
        this.calledByStaffName = null;
        this.counterNumber = null;
    }

    /**
     * Marks consultation as started.
     *
     * @param staffId ID of clinician starting consultation
     */
    public void markInProgress(String staffId) {
        this.status = QueueStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
        this.startedByStaffId = staffId;
    }

    /**
     * Applies appointment-time priority boost if needed.
     *
     * @return true when state changed
     */
    public boolean applyAppointmentPriorityBoost() {
        if (this.appointmentPriorityBoostApplied) {
            return false;
        }
        this.appointmentPriorityBoostApplied = true;
        this.appointmentPriorityBoostAppliedAt = Instant.now();
        this.effectivePriority = calculateEffectivePriority(this.triageLevel, this.priorityModifier, true);
        return true;
    }

    /**
     * Marks consultation as completed.
     *
     * @param staffId ID of clinician completing consultation
     */
    public void markCompleted(String staffId) {
        this.status = QueueStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.completedByStaffId = staffId;
    }

    /**
     * Marks patient as no-show after multiple missed calls.
     *
     * @param staffId ID of staff marking no-show
     */
    public void markNoShow(String staffId) {
        this.status = QueueStatus.NO_SHOW;
        this.completedAt = Instant.now();
        this.completedByStaffId = staffId;
    }

    /**
     * Clears appointment-time priority boost if needed.
     *
     * @return true when state changed
     */
    public boolean clearAppointmentPriorityBoost() {
        if (!this.appointmentPriorityBoostApplied) {
            return false;
        }
        this.appointmentPriorityBoostApplied = false;
        this.effectivePriority = calculateEffectivePriority(this.triageLevel, this.priorityModifier, false);
        return true;
    }

    // ==================== CALCULATED PROPERTIES ====================

    /**
     * Cancels the queue ticket.
     *
     * @param staffId ID of staff cancelling ticket
     */
    public void markCancelled(String staffId) {
        this.status = QueueStatus.CANCELLED;
        this.completedAt = Instant.now();
        this.completedByStaffId = staffId;
    }

    /**
     * Returns the target wait time based on triage level.
     *
     * @return target wait time in minutes
     */
    public int getTargetWaitMinutes() {
        return triageLevel.getTargetWaitMinutes();
    }

    /**
     * Checks if patient has exceeded their target wait time.
     *
     * @return true if waiting time exceeds target
     */
    public boolean isOverdue() {
        if (status != QueueStatus.WAITING) {
            return false;
        }
        long waitedMinutes = java.time.Duration.between(createdAt, Instant.now()).toMinutes();
        return waitedMinutes > triageLevel.getTargetWaitMinutes();
    }

    /**
     * Calculates current wait time in minutes.
     *
     * @return wait time in minutes
     */
    public long getWaitTimeMinutes() {
        Instant endTime = (startedAt != null) ? startedAt : Instant.now();
        return java.time.Duration.between(createdAt, endTime).toMinutes();
    }

    /**
     * Calculates consultation duration in minutes (if completed).
     *
     * @return consultation time in minutes, or 0 if not started
     */
    public long getConsultationTimeMinutes() {
        if (startedAt == null) {
            return 0;
        }
        Instant endTime = (completedAt != null) ? completedAt : Instant.now();
        return java.time.Duration.between(startedAt, endTime).toMinutes();
    }

    /**
     * Returns a triaged patient back to the waiting queue for consultation.
     *
     * <p>This transition is used after nurse triage is completed. It clears
     * active call/session metadata so the patient can be called again by
     * physician workflow.</p>
     */
    public void returnToWaitingAfterTriage() {
        this.status = QueueStatus.WAITING;
        this.calledAt = null;
        this.calledByStaffId = null;
        this.calledByStaffName = null;
        this.counterNumber = null;
        this.startedAt = null;
        this.startedByStaffId = null;
        this.completedAt = null;
        this.completedByStaffId = null;
    }

    /**
     * Marks patient as admitted.
     *
     * @param staffId ID of staff finalizing admission
     * @param reason  reason or note for admission decision
     */
    public void markAdmitted(String staffId, String reason) {
        this.status = QueueStatus.ADMITTED;
        this.completedAt = Instant.now();
        this.completedByStaffId = staffId;
        this.admissionReason = sanitizeText(reason, 500);
    }

    // ==================== ENUMS ====================

    /**
     * Queue categories representing different service areas or patient types.
     */
    public enum QueueCategory {
        /**
         * General outpatient services
         */
        GENERAL("WK", "General"),
        /**
         * Emergency department
         */
        EMERGENCY("EM", "Emergency"),
        /**
         * Maternal and obstetric services
         */
        MATERNAL("MT", "Maternal"),
        /**
         * Pediatric services (children)
         */
        PEDIATRIC("PD", "Pediatric"),
        /**
         * Follow-up appointments
         */
        FOLLOW_UP("PR", "Follow-up"),
        /**
         * Laboratory services
         */
        LABORATORY("LB", "Laboratory"),
        /**
         * Pharmacy pickup
         */
        PHARMACY("PH", "Pharmacy"),
        /**
         * Radiology/imaging
         */
        RADIOLOGY("RD", "Radiology");

        private final String prefix;
        private final String displayName;

        QueueCategory(String prefix, String displayName) {
            this.prefix = prefix;
            this.displayName = displayName;
        }

        /**
         * Returns the ticket number prefix for this category.
         *
         * @return single-letter prefix
         */
        public String getPrefix() {
            return prefix;
        }

        /**
         * Returns the human-readable display name.
         *
         * @return display name
         */
        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Status of a queue ticket through its lifecycle.
     */
    public enum QueueStatus {
        /**
         * Patient is waiting to be called
         */
        WAITING,
        /**
         * Patient has been called to a counter
         */
        CALLED,
        /**
         * Consultation is in progress
         */
        IN_PROGRESS,
        /**
         * Consultation completed successfully
         */
        COMPLETED,
        /**
         * Patient did not respond after being called
         */
        NO_SHOW,
        /**
         * Patient admitted for inpatient care.
         */
        ADMITTED,
        /**
         * Ticket was cancelled
         */
        CANCELLED
    }
}
