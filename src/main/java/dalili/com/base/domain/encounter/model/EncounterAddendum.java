package dalili.com.base.domain.encounter.model;


import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an addendum to a completed clinical encounter.
 *
 * <p>Addendums provide a mechanism to append additional information to
 * completed encounters without modifying the original record. This maintains
 * the integrity of the clinical documentation while allowing for:
 * <ul>
 *   <li>Corrections to errors discovered after completion</li>
 *   <li>Additional clinical observations</li>
 *   <li>Follow-up notes</li>
 *   <li>Lab result interpretations</li>
 *   <li>Clarifications requested by other providers</li>
 * </ul>
 * </p>
 *
 * <h3>Immutability Guarantees:</h3>
 * <ul>
 *   <li>Addendums cannot be edited once created</li>
 *   <li>Addendums cannot be deleted</li>
 *   <li>Corrections to addendums require a new addendum</li>
 *   <li>All addendums are timestamped and attributed</li>
 * </ul>
 *
 * <h3>Audit Trail:</h3>
 * <p>Each addendum creates a permanent, immutable record in the audit log.
 * The chain of addendums provides a complete history of all modifications
 * to the encounter documentation.</p>
 *
 * @see Encounter
 */
@Entity
@Table(name = "encounter_addendums", indexes = {
        @Index(name = "idx_addendum_encounter", columnList = "encounterId"),
        @Index(name = "idx_addendum_author", columnList = "authorId"),
        @Index(name = "idx_addendum_created", columnList = "createdAt")
})
@Getter
public class EncounterAddendum {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * The encounter this addendum belongs to.
     * Addendums can only be added to COMPLETED encounters.
     */
    @Column(nullable = false)
    private UUID encounterId;

    /**
     * The patient ID (denormalized for query efficiency).
     */
    @Column(nullable = false)
    private UUID patientId;

    /**
     * Reference to parent addendum if this is a correction.
     * Null if this is a direct addendum to the encounter.
     */
    @Column
    private UUID parentAddendumId;

    /**
     * The type of addendum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AddendumType type;

    /**
     * The reason for creating this addendum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AddendumReason reason;

    /**
     * The addendum content.
     * Immutable once created.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Reference to specific section being amended (optional).
     * E.g., "ASSESSMENT", "PLAN", "DIAGNOSIS", "MEDICATION"
     */
    @Column(length = 100)
    private String sectionReference;

    /**
     * If correcting a diagnosis, the original ICD code.
     */
    @Column(length = 20)
    private String originalDiagnosisCode;

    /**
     * If correcting a diagnosis, the corrected ICD code.
     */
    @Column(length = 20)
    private String correctedDiagnosisCode;

    /**
     * If correcting a medication, the original medication name.
     */
    @Column
    private String originalMedication;

    /**
     * If correcting a medication, the corrected medication name.
     */
    @Column
    private String correctedMedication;

    // ==================== AUTHOR INFORMATION ====================

    /**
     * Staff ID of the addendum author.
     */
    @Column(nullable = false)
    private UUID authorId;

    /**
     * Author's name at time of addendum (snapshot).
     */
    @Column(nullable = false)
    private String authorName;

    /**
     * Author's role at time of addendum (snapshot).
     */
    @Column(nullable = false)
    private String authorRole;

    /**
     * Author's credentials (e.g., "MD", "RN", "DO").
     */
    @Column(length = 50)
    private String authorCredentials;

    // ==================== TIMESTAMPS ====================

    /**
     * When this addendum was created.
     * Immutable - set once at creation.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Signature timestamp - when author confirmed the addendum.
     * For legal purposes, may differ from createdAt.
     */
    @Column(nullable = false)
    private Instant signedAt;

    /**
     * Hash of the content for integrity verification.
     */
    @Column(length = 64)
    private String contentHash;

    protected EncounterAddendum() {
    }

    /**
     * Creates a new addendum to a completed encounter.
     *
     * <p>This is the only way to create an addendum. Once created,
     * the addendum is immutable.</p>
     *
     * @param encounterId the completed encounter UUID
     * @param patientId   the patient UUID
     * @param type        the type of addendum
     * @param reason      the reason for the addendum
     * @param content     the addendum content
     * @param authorId    the author's staff UUID
     * @param authorName  the author's name
     * @param authorRole  the author's role
     * @return a new immutable EncounterAddendum
     * @throws IllegalArgumentException if content is blank
     */
    public static EncounterAddendum create(
            UUID encounterId,
            UUID patientId,
            AddendumType type,
            AddendumReason reason,
            String content,
            UUID authorId,
            String authorName,
            String authorRole
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Addendum content cannot be blank");
        }

        EncounterAddendum addendum = new EncounterAddendum();
        addendum.encounterId = encounterId;
        addendum.patientId = patientId;
        addendum.type = type;
        addendum.reason = reason;
        addendum.content = content;
        addendum.authorId = authorId;
        addendum.authorName = authorName;
        addendum.authorRole = authorRole;
        addendum.createdAt = Instant.now();
        addendum.signedAt = Instant.now();
        addendum.contentHash = computeHash(content);
        return addendum;
    }

    /**
     * Creates a correction addendum that references a parent addendum.
     *
     * @param parentAddendum the addendum being corrected
     * @param reason         the correction reason
     * @param content        the correction content
     * @param authorId       the author's staff UUID
     * @param authorName     the author's name
     * @param authorRole     the author's role
     * @return a new correction addendum
     */
    public static EncounterAddendum createCorrection(
            EncounterAddendum parentAddendum,
            AddendumReason reason,
            String content,
            UUID authorId,
            String authorName,
            String authorRole
    ) {
        EncounterAddendum addendum = create(
                parentAddendum.encounterId,
                parentAddendum.patientId,
                AddendumType.CORRECTION,
                reason,
                content,
                authorId,
                authorName,
                authorRole
        );
        addendum.parentAddendumId = parentAddendum.id;
        return addendum;
    }

    /**
     * Computes SHA-256 hash of content for integrity verification.
     */
    private static String computeHash(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Sets the section reference for targeted amendments.
     * Can only be set during creation (builder pattern).
     *
     * @param sectionReference the section being amended
     * @return this addendum for chaining
     */
    public EncounterAddendum withSectionReference(String sectionReference) {
        if (this.id != null) {
            throw new IllegalStateException("Cannot modify addendum after persistence");
        }
        this.sectionReference = sectionReference;
        return this;
    }

    /**
     * Sets diagnosis correction details.
     * Can only be set during creation.
     *
     * @param originalCode  the original ICD-10 code
     * @param correctedCode the corrected ICD-10 code
     * @return this addendum for chaining
     */
    public EncounterAddendum withDiagnosisCorrection(String originalCode, String correctedCode) {
        if (this.id != null) {
            throw new IllegalStateException("Cannot modify addendum after persistence");
        }
        this.originalDiagnosisCode = originalCode;
        this.correctedDiagnosisCode = correctedCode;
        return this;
    }

    /**
     * Sets medication correction details.
     * Can only be set during creation.
     *
     * @param originalMed  the original medication
     * @param correctedMed the corrected medication
     * @return this addendum for chaining
     */
    public EncounterAddendum withMedicationCorrection(String originalMed, String correctedMed) {
        if (this.id != null) {
            throw new IllegalStateException("Cannot modify addendum after persistence");
        }
        this.originalMedication = originalMed;
        this.correctedMedication = correctedMed;
        return this;
    }

    /**
     * Sets author credentials.
     * Can only be set during creation.
     *
     * @param credentials the author's credentials
     * @return this addendum for chaining
     */
    public EncounterAddendum withCredentials(String credentials) {
        if (this.id != null) {
            throw new IllegalStateException("Cannot modify addendum after persistence");
        }
        this.authorCredentials = credentials;
        return this;
    }

    /**
     * Verifies the content integrity using the stored hash.
     *
     * @return true if content has not been tampered with
     */
    public boolean verifyIntegrity() {
        if (contentHash == null) {
            return false;
        }
        return contentHash.equals(computeHash(content));
    }

    /**
     * Checks if this addendum is a correction to another addendum.
     *
     * @return true if this is a correction addendum
     */
    public boolean isCorrection() {
        return parentAddendumId != null;
    }

    /**
     * Gets a formatted signature line for display.
     *
     * @return formatted signature
     */
    public String getSignatureLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(authorName);
        if (authorCredentials != null && !authorCredentials.isBlank()) {
            sb.append(", ").append(authorCredentials);
        }
        sb.append(" (").append(authorRole).append(")");
        sb.append(" - ").append(signedAt);
        return sb.toString();
    }

    // ==================== ENUMS ====================

    /**
     * Type of addendum.
     */
    public enum AddendumType {
        /**
         * Additional clinical information
         */
        ADDITION,
        /**
         * Correction to existing information
         */
        CORRECTION,
        /**
         * Clarification of existing information
         */
        CLARIFICATION,
        /**
         * Late entry (documentation delayed)
         */
        LATE_ENTRY,
        /**
         * Follow-up note
         */
        FOLLOW_UP,
        /**
         * Lab/test result interpretation
         */
        RESULT_INTERPRETATION,
        /**
         * Response to query from another provider
         */
        QUERY_RESPONSE
    }

    /**
     * Reason for creating the addendum.
     */
    public enum AddendumReason {
        /**
         * Error discovered in original documentation
         */
        ERROR_CORRECTION,
        /**
         * Additional information became available
         */
        NEW_INFORMATION,
        /**
         * Clarification requested by another provider
         */
        CLARIFICATION_REQUESTED,
        /**
         * Documentation was delayed
         */
        DELAYED_DOCUMENTATION,
        /**
         * Test results returned after encounter
         */
        TEST_RESULTS,
        /**
         * Patient provided additional history
         */
        PATIENT_UPDATE,
        /**
         * Consultation findings
         */
        CONSULTATION_FINDINGS,
        /**
         * Medication reconciliation update
         */
        MEDICATION_UPDATE,
        /**
         * Diagnosis refinement based on new data
         */
        DIAGNOSIS_REFINEMENT,
        /**
         * Quality review finding
         */
        QUALITY_REVIEW,
        /**
         * Legal/compliance requirement
         */
        LEGAL_REQUIREMENT,
        /**
         * Other reason (specify in content)
         */
        OTHER
    }
}