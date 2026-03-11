package dalili.com.base.domain.encounter.repository;

import dalili.com.base.domain.encounter.model.Encounter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for encounter persistence.
 *
 * <p>Extends ImmutableClinicalRepository to prevent deletion.
 * Encounters can never be deleted - only cancelled or amended.</p>
 */
@Repository
public interface EncounterRepository extends ImmutableClinicalRepository<Encounter> {

    /**
     * Finds encounters for a patient ordered by date descending.
     */
    List<Encounter> findByPatientIdOrderByStartedAtDesc(UUID patientId);

    /**
     * Finds encounters by clinician within a date range.
     */
    List<Encounter> findByClinicianIdAndStartedAtBetweenOrderByStartedAtDesc(
            UUID clinicianId, Instant start, Instant end
    );

    /**
     * Finds encounter by queue ticket.
     */
    Optional<Encounter> findByQueueTicketId(UUID queueTicketId);

    /**
     * Finds encounters by clinician and status.
     */
    List<Encounter> findByClinicianIdAndStatus(UUID clinicianId, Encounter.EncounterStatus status);

    /**
     * Finds completed encounters for a patient with a specific clinician.
     */
    List<Encounter> findByPatientIdAndClinicianIdAndStatusOrderByStartedAtDesc(
            UUID patientId,
            UUID clinicianId,
            Encounter.EncounterStatus status
    );

    /**
     * Counts encounters with AI accuracy ratings within a date range.
     */
    long countByAiAccuracyRatingAndStartedAtBetween(
            Encounter.NoteAccuracyRating rating, Instant start, Instant end
    );

    /**
     * Finds encounters by status.
     */
    List<Encounter> findByStatus(Encounter.EncounterStatus status);

    /**
     * Finds encounters started within a date range.
     */
    List<Encounter> findByStartedAtBetweenOrderByStartedAtDesc(Instant start, Instant end);

    /**
     * Counts encounters by status within a date range.
     */
    long countByStatusAndStartedAtBetween(Encounter.EncounterStatus status, Instant start, Instant end);

    /**
     * Finds completed encounters for a patient.
     */
    @Query("SELECT e FROM Encounter e WHERE e.patientId = :patientId " +
            "AND e.status = 'COMPLETED' ORDER BY e.completedAt DESC")
    List<Encounter> findCompletedByPatientId(UUID patientId);

    /**
     * Finds patient IDs in a queue slice who were previously managed by the clinician.
     */
    @Query("SELECT DISTINCT e.patientId FROM Encounter e WHERE e.clinicianId = :clinicianId " +
            "AND e.status = 'COMPLETED' AND e.patientId IN :patientIds")
    List<UUID> findDistinctCompletedPatientIdsByClinicianIn(
            UUID clinicianId,
            List<UUID> patientIds
    );

    /**
     * Finds encounters with confirmed notes for AI metrics.
     */
    @Query("SELECT e FROM Encounter e WHERE e.noteConfirmed = true " +
            "AND e.aiAccuracyRating IS NOT NULL " +
            "AND e.startedAt BETWEEN :start AND :end " +
            "ORDER BY e.startedAt DESC")
    List<Encounter> findWithAiRatings(Instant start, Instant end);
}
