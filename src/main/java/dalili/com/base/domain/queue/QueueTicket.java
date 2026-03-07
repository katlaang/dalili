package dalili.com.base.domain.queue;

import dalili.com.base.domain.triage.TriageLevel;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
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
     * Human-readable ticket number (e.g., "A-001", "E-015").
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
     * Flag indicating triage assessment has been completed.
     */
    @Column(nullable = false)
    private boolean triaged = false;

    /**
     * Reference to the triage assessment (set after triage completed).
     */
    @Column
    private UUID triageAssessmentId;

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
     * @param ticketNumber     the formatted ticket number (e.g., "A-001")
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
        ticket.effectivePriority = calculateEffectivePriority(TriageLevel.GREEN, priorityModifier);
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
        ticket.effectivePriority = calculateEffectivePriority(TriageLevel.ORANGE, PriorityModifier.AMBULANCE_ARRIVAL);
        return ticket;
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
        this.effectivePriority = calculateEffectivePriority(newTriageLevel, this.priorityModifier);
    }

    // ==================== STATUS TRANSITIONS ====================

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
        this.effectivePriority = calculateEffectivePriority(newTriageLevel, PriorityModifier.DETERIORATING);
        this.escalationReason = reason;
        this.lastEscalatedAt = Instant.now();
        this.escalatedByStaffId = staffId;
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

    // ==================== ENUMS ====================

    /**
     * Queue categories representing different service areas or patient types.
     */
    public enum QueueCategory {
        /**
         * General outpatient services
         */
        GENERAL("A", "General"),
        /**
         * Emergency department
         */
        EMERGENCY("E", "Emergency"),
        /**
         * Maternal and obstetric services
         */
        MATERNAL("M", "Maternal"),
        /**
         * Pediatric services (children)
         */
        PEDIATRIC("P", "Pediatric"),
        /**
         * Follow-up appointments
         */
        FOLLOW_UP("F", "Follow-up"),
        /**
         * Laboratory services
         */
        LABORATORY("L", "Laboratory"),
        /**
         * Pharmacy pickup
         */
        PHARMACY("R", "Pharmacy"),
        /**
         * Radiology/imaging
         */
        RADIOLOGY("X", "Radiology");

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
         * Ticket was cancelled
         */
        CANCELLED
    }
}