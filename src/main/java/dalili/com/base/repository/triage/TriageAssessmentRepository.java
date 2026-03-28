package dalili.com.base.repository.triage;

import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.domain.triage.TriageLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for triage assessment persistence and queries.
 *
 * <p>Provides methods for retrieving triage assessments by patient,
 * queue ticket, and date ranges.</p>
 */
@Repository
public interface TriageAssessmentRepository extends JpaRepository<TriageAssessment, UUID> {

    /**
     * Finds the most recent assessment for a queue ticket.
     *
     * @param queueTicketId the queue ticket UUID
     * @return the most recent assessment if any
     */
    Optional<TriageAssessment> findTopByQueueTicketIdOrderByAssessedAtDesc(UUID queueTicketId);

    /**
     * Finds all assessments for a queue ticket (including reassessments).
     *
     * @param queueTicketId the queue ticket UUID
     * @return list of assessments ordered by time
     */
    List<TriageAssessment> findByQueueTicketIdOrderByAssessedAtDesc(UUID queueTicketId);

    /**
     * Finds all assessments for a patient.
     *
     * @param patientId the patient UUID
     * @return list of assessments ordered by time descending
     */
    List<TriageAssessment> findByPatientIdOrderByAssessedAtDesc(UUID patientId);

    /**
     * Finds assessments within a time range.
     *
     * @param start start of range
     * @param end   end of range
     * @return list of assessments
     */
    List<TriageAssessment> findByAssessedAtBetweenOrderByAssessedAtDesc(Instant start, Instant end);

    /**
     * Finds assessments performed by a specific staff member within a time range.
     */
    List<TriageAssessment> findByAssessedByStaffIdAndAssessedAtBetweenOrderByAssessedAtDesc(
            String assessedByStaffId,
            Instant start,
            Instant end
    );

    /**
     * Finds assessments with triage overrides within a time range.
     * Used for quality review of clinical judgment vs system recommendations.
     *
     * @param start start of range
     * @param end   end of range
     * @return list of overridden assessments
     */
    List<TriageAssessment> findByTriageOverriddenTrueAndAssessedAtBetweenOrderByAssessedAtDesc(
            Instant start, Instant end
    );

    /**
     * Counts assessments by final triage level within a time range.
     *
     * @param triageLevel the triage level
     * @param start       start of range
     * @param end         end of range
     * @return count of assessments
     */
    long countByFinalTriageLevelAndAssessedAtBetween(TriageLevel triageLevel, Instant start, Instant end);
}
