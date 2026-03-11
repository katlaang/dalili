package dalili.com.base.repository.queue;

import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for queue ticket persistence and queries.
 *
 * <p>Provides methods for queue management including:
 * <ul>
 *   <li>Priority-ordered queue retrieval</li>
 *   <li>Ticket number generation</li>
 *   <li>Statistics and reporting</li>
 *   <li>Overdue ticket identification</li>
 * </ul>
 * </p>
 *
 * @see QueueTicket
 */
@Repository
public interface QueueTicketRepository extends JpaRepository<QueueTicket, UUID> {

    // ==================== QUEUE RETRIEVAL ====================

    /**
     * Finds all waiting tickets for a date, ordered by priority then check-in time.
     *
     * <p>This is the primary method for getting the "call next" queue.
     * Tickets are ordered by effective priority (ascending) then created time,
     * ensuring highest priority patients are seen first.</p>
     *
     * @param queueDate the date to query
     * @param status    the status to filter by (typically WAITING)
     * @return list of tickets in priority order
     */
    List<QueueTicket> findByQueueDateAndStatusOrderByEffectivePriorityAscCreatedAtAsc(
            LocalDate queueDate,
            QueueTicket.QueueStatus status
    );

    /**
     * Finds all tickets for a date regardless of status.
     *
     * <p>Used for queue dashboard display showing all patients.</p>
     *
     * @param queueDate the date to query
     * @return list of tickets ordered by creation time
     */
    List<QueueTicket> findByQueueDateOrderByCreatedAtAsc(LocalDate queueDate);

    /**
     * Finds all tickets for a date and category.
     *
     * <p>Used for department-specific queue views.</p>
     *
     * @param queueDate the date to query
     * @param category  the category to filter by
     * @return list of tickets ordered by priority then creation time
     */
    List<QueueTicket> findByQueueDateAndCategoryOrderByEffectivePriorityAscCreatedAtAsc(
            LocalDate queueDate,
            QueueTicket.QueueCategory category
    );

    /**
     * Finds waiting tickets that have not been triaged yet.
     *
     * <p>Used to identify patients waiting for nurse triage assessment.</p>
     *
     * @param queueDate the date to query
     * @param status    should be WAITING
     * @param triaged   should be false
     * @return list of untriaged tickets in order of arrival
     */
    List<QueueTicket> findByQueueDateAndStatusAndTriagedOrderByCreatedAtAsc(
            LocalDate queueDate,
            QueueTicket.QueueStatus status,
            boolean triaged
    );

    /**
     * Finds waiting tickets that have been triaged.
     *
     * <p>Used to get the main physician/consultation queue.</p>
     *
     * @param queueDate the date to query
     * @param status    should be WAITING
     * @param triaged   should be true
     * @return list of triaged tickets in priority order
     */
    List<QueueTicket> findByQueueDateAndStatusAndTriagedOrderByEffectivePriorityAscCreatedAtAsc(
            LocalDate queueDate,
            QueueTicket.QueueStatus status,
            boolean triaged
    );

    // ==================== DUPLICATE PREVENTION ====================

    /**
     * Finds an active ticket for a patient on a given date.
     *
     * <p>Used to prevent duplicate check-ins. If a patient already has
     * an active ticket (WAITING, CALLED, or IN_PROGRESS), returns that
     * ticket instead of creating a new one.</p>
     *
     * @param patientId the patient's UUID
     * @param queueDate the date to query
     * @param statuses  list of active statuses to check
     * @return the existing active ticket if any
     */
    Optional<QueueTicket> findByPatientIdAndQueueDateAndStatusIn(
            UUID patientId,
            LocalDate queueDate,
            List<QueueTicket.QueueStatus> statuses
    );

    /**
     * Finds active tickets for a patient by date and category.
     */
    List<QueueTicket> findByPatientIdAndQueueDateAndStatusInAndCategory(
            UUID patientId,
            LocalDate queueDate,
            List<QueueTicket.QueueStatus> statuses,
            QueueTicket.QueueCategory category
    );

    // ==================== TICKET NUMBER GENERATION ====================

    /**
     * Finds the maximum ticket number for a category on a date.
     *
     * <p>Used for generating sequential ticket numbers. Extracts the
     * numeric portion after the category prefix (e.g., "001" from "A-001").</p>
     *
     * @param queueDate the date to query
     * @return the highest ticket sequence number, or empty if none exist
     */
    @Query("SELECT MAX(CAST(SUBSTRING(q.ticketNumber, LENGTH(:prefix) + 2) AS int)) FROM QueueTicket q " +
            "WHERE q.queueDate = :queueDate AND q.ticketNumber LIKE CONCAT(:prefix, '-%')")
    Optional<Integer> findMaxTicketNumberForDateAndPrefix(
            LocalDate queueDate,
            String prefix
    );

    // ==================== STATISTICS ====================

    /**
     * Counts tickets by date and status.
     *
     * <p>Used for queue statistics dashboard.</p>
     *
     * @param queueDate the date to query
     * @param status    the status to count
     * @return count of matching tickets
     */
    long countByQueueDateAndStatus(LocalDate queueDate, QueueTicket.QueueStatus status);

    /**
     * Counts tickets by date, status, and triage level.
     *
     * <p>Used for detailed queue statistics showing breakdown by acuity.</p>
     *
     * @param queueDate   the date to query
     * @param status      the status to filter
     * @param triageLevel the triage level to filter
     * @return count of matching tickets
     */
    long countByQueueDateAndStatusAndTriageLevel(
            LocalDate queueDate,
            QueueTicket.QueueStatus status,
            TriageLevel triageLevel
    );

    /**
     * Counts tickets by date and category.
     *
     * @param queueDate the date to query
     * @param category  the category to count
     * @return count of matching tickets
     */
    long countByQueueDateAndCategory(LocalDate queueDate, QueueTicket.QueueCategory category);

    /**
     * Counts untriaged waiting tickets.
     *
     * @param queueDate the date to query
     * @param status    should be WAITING
     * @param triaged   should be false
     * @return count of untriaged waiting tickets
     */
    long countByQueueDateAndStatusAndTriaged(
            LocalDate queueDate,
            QueueTicket.QueueStatus status,
            boolean triaged
    );

    // ==================== OVERDUE IDENTIFICATION ====================

    /**
     * Finds all waiting tickets ordered by triage level and time.
     *
     * <p>Used by overdue checking logic to identify patients who have
     * exceeded their target wait time based on triage level.</p>
     *
     * @param queueDate the date to query
     * @param status    should be WAITING
     * @return list of waiting tickets
     */
    @Query("SELECT q FROM QueueTicket q WHERE q.queueDate = :queueDate " +
            "AND q.status = :status ORDER BY q.triageLevel ASC, q.createdAt ASC")
    List<QueueTicket> findWaitingTicketsForOverdueCheck(
            LocalDate queueDate,
            QueueTicket.QueueStatus status
    );

    // ==================== PATIENT HISTORY ====================

    /**
     * Finds all tickets for a patient, ordered by date descending.
     *
     * <p>Used to view a patient's visit history.</p>
     *
     * @param patientId the patient's UUID
     * @return list of tickets ordered by most recent first
     */
    List<QueueTicket> findByPatientIdOrderByQueueDateDescCreatedAtDesc(UUID patientId);

    /**
     * Finds tickets for a patient within a date range.
     *
     * @param patientId the patient's UUID
     * @param startDate start of range (inclusive)
     * @param endDate   end of range (inclusive)
     * @return list of tickets
     */
    List<QueueTicket> findByPatientIdAndQueueDateBetweenOrderByQueueDateDesc(
            UUID patientId,
            LocalDate startDate,
            LocalDate endDate
    );
}
