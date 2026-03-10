package dalili.com.base.domain.encounter.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a diagnosis assigned during an encounter.
 *
 * <p>Diagnoses use ICD-10 coding and can be marked as primary or secondary.
 * Each diagnosis is linked to the encounter where it was made.</p>
 */
@Entity
@Table(name = "diagnoses", indexes = {
        @Index(name = "idx_diagnosis_encounter", columnList = "encounter_id"),
        @Index(name = "idx_diagnosis_icd", columnList = "icdCode")
})
@Getter
public class Diagnosis {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * The encounter this diagnosis belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "encounter_id", nullable = false)
    @Setter
    private Encounter encounter;

    /**
     * ICD-10 diagnosis code.
     */
    @Column(length = 20, nullable = false)
    private String icdCode;

    /**
     * Diagnosis description/name.
     */
    @Column(length = 500, nullable = false)
    private String description;

    /**
     * Whether this is the primary diagnosis.
     */
    @Column(nullable = false)
    private boolean isPrimary;

    /**
     * Type of diagnosis.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiagnosisType type;

    /**
     * Clinical notes about this diagnosis.
     */
    @Column(length = 1000)
    private String notes;

    /**
     * When this diagnosis was recorded.
     */
    @Column(nullable = false)
    private Instant recordedAt;

    /**
     * Staff ID who recorded this diagnosis.
     */
    @Column(nullable = false)
    private String recordedBy;

    protected Diagnosis() {
    }

    /**
     * Creates a new diagnosis.
     *
     * @param icdCode     the ICD-10 code
     * @param description the diagnosis description
     * @param isPrimary   whether this is the primary diagnosis
     * @param type        the diagnosis type
     * @param recordedBy  staff ID recording the diagnosis
     * @return a new Diagnosis
     */
    public static Diagnosis create(
            String icdCode,
            String description,
            boolean isPrimary,
            DiagnosisType type,
            String recordedBy
    ) {
        Diagnosis diagnosis = new Diagnosis();
        diagnosis.icdCode = icdCode;
        diagnosis.description = description;
        diagnosis.isPrimary = isPrimary;
        diagnosis.type = type;
        diagnosis.recordedBy = recordedBy;
        diagnosis.recordedAt = Instant.now();
        return diagnosis;
    }

    /**
     * Adds clinical notes to this diagnosis.
     *
     * @param notes the clinical notes
     */
    public void addNotes(String notes) {
        this.notes = notes;
    }

    /**
     * Type of diagnosis.
     */
    public enum DiagnosisType {
        /**
         * Confirmed diagnosis
         */
        CONFIRMED,
        /**
         * Working diagnosis (under investigation)
         */
        WORKING,
        /**
         * Differential diagnosis (possible)
         */
        DIFFERENTIAL,
        /**
         * Ruled out
         */
        RULED_OUT
    }
}
