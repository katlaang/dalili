package dalili.com.base.domain.encounter.repository;

import dalili.com.base.domain.encounter.model.EncounterAddendum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for encounter addendum persistence.
 *
 * <p>Note: This repository intentionally does not provide update or delete
 * operations. Addendums are immutable once created.</p>
 */
@Repository
public interface EncounterAddendumRepository extends JpaRepository<EncounterAddendum, UUID> {

    /**
     * Finds all addendums for an encounter, ordered by creation time.
     *
     * @param encounterId the encounter UUID
     * @return list of addendums in chronological order
     */
    List<EncounterAddendum> findByEncounterIdOrderByCreatedAtAsc(UUID encounterId);

    /**
     * Finds all addendums for a patient across all encounters.
     *
     * @param patientId the patient UUID
     * @return list of addendums ordered by creation time descending
     */
    List<EncounterAddendum> findByPatientIdOrderByCreatedAtDesc(UUID patientId);

    /**
     * Finds corrections to a specific addendum.
     *
     * @param parentAddendumId the parent addendum UUID
     * @return list of correction addendums
     */
    List<EncounterAddendum> findByParentAddendumIdOrderByCreatedAtAsc(UUID parentAddendumId);

    /**
     * Finds addendums by author within a date range.
     *
     * @param authorId the author UUID
     * @param start    start of range
     * @param end      end of range
     * @return list of addendums
     */
    List<EncounterAddendum> findByAuthorIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID authorId, Instant start, Instant end
    );

    /**
     * Counts addendums for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return count of addendums
     */
    long countByEncounterId(UUID encounterId);

    /**
     * Finds addendums by type for an encounter.
     *
     * @param encounterId the encounter UUID
     * @param type        the addendum type
     * @return list of addendums of the specified type
     */
    List<EncounterAddendum> findByEncounterIdAndTypeOrderByCreatedAtAsc(
            UUID encounterId, EncounterAddendum.AddendumType type
    );

    /**
     * Checks if any addendums exist for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return true if addendums exist
     */
    boolean existsByEncounterId(UUID encounterId);

    /**
     * Finds diagnosis correction addendums for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return list of diagnosis corrections
     */
    @Query("SELECT a FROM EncounterAddendum a WHERE a.encounterId = :encounterId " +
            "AND a.originalDiagnosisCode IS NOT NULL ORDER BY a.createdAt ASC")
    List<EncounterAddendum> findDiagnosisCorrections(UUID encounterId);

    /**
     * Finds medication correction addendums for an encounter.
     *
     * @param encounterId the encounter UUID
     * @return list of medication corrections
     */
    @Query("SELECT a FROM EncounterAddendum a WHERE a.encounterId = :encounterId " +
            "AND a.originalMedication IS NOT NULL ORDER BY a.createdAt ASC")
    List<EncounterAddendum> findMedicationCorrections(UUID encounterId);
}
