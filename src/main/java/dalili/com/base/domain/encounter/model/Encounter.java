package dalili.com.base.domain.encounter.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a clinical encounter (visit) between a patient and clinician.
 *
 * <p>An encounter is the container for all clinical documentation during a
 * single patient visit, including:
 * <ul>
 *   <li>Triage assessment (linked via queue ticket)</li>
 *   <li>Clinical notes (transcript, AI draft, physician note)</li>
 *   <li>Diagnosis (ICD-10 codes)</li>
 *   <li>Medication orders</li>
 * </ul>
 * </p>
 *
 * <p>The encounter follows a workflow:
 * OPEN → DOCUMENTING → PENDING_PHARMACY → COMPLETED</p>
 *
 * <h3>Safety Invariants:</h3>
 * <ul>
 *   <li>Transcript can only be recorded once</li>
 *   <li>AI draft requires transcript and can only be recorded once</li>
 *   <li>Note confirmation requires physician-authored note and can only happen once</li>
 *   <li>Encounter completion requires confirmed note</li>
 *   <li>Completed encounters are immutable (soft lock)</li>
 * </ul>
 *
 * <h3>Amendment Workflow (Future):</h3>
 * <p>For production, amendments to completed encounters should create
 * versioned addendums rather than modifying the original record.</p>
 *
 * @see Diagnosis
 * @see MedicationOrder
 */
@Entity
@Table(name = "encounters", indexes = {
        @Index(name = "idx_encounter_patient", columnList = "patientId"),
        @Index(name = "idx_encounter_clinician", columnList = "clinicianId"),
        @Index(name = "idx_encounter_date", columnList = "startedAt"),
        @Index(name = "idx_encounter_queue", columnList = "queueTicketId")
})
@Getter
public class Encounter {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Reference to the patient record.
     */
    @Column(nullable = false)
    private UUID patientId;

    /**
     * Reference to the queue ticket that initiated this encounter.
     */
    @Column
    private UUID queueTicketId;

    /**
     * Reference to the triage assessment (if performed).
     */
    @Column
    private UUID triageAssessmentId;

    /**
     * Primary clinician responsible for this encounter.
     */
    @Column(nullable = false)
    private UUID clinicianId;

    /**
     * Clinician's name at time of encounter (snapshot for audit immutability).
     */
    @Column(nullable = false)
    private String clinicianName;

    /**
     * Clinician's role at time of encounter (snapshot for audit immutability).
     */
    @Column(nullable = false)
    private String clinicianRole;

    /**
     * Type of encounter.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EncounterType encounterType;

    /**
     * Current status of the encounter.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EncounterStatus status;

    /**
     * Chief complaint from triage or patient.
     */
    @Column(length = 500)
    private String chiefComplaint;

    // ==================== CLINICAL NOTES ====================

    /**
     * Raw transcript from ambient AI recording.
     * Immutable once set.
     */
    @Column(columnDefinition = "TEXT")
    private String transcript;

    /**
     * Timestamp when transcript was recorded.
     */
    @Column
    private Instant transcriptRecordedAt;

    /**
     * AI-generated draft SOAP note (for comparison).
     * Immutable once set.
     */
    @Column(columnDefinition = "TEXT")
    private String aiDraftNote;

    /**
     * Physician's own authored note (ground truth).
     */
    @Column(columnDefinition = "TEXT")
    private String physicianAuthoredNote;

    /**
     * Final confirmed clinical note (official record).
     * Immutable once confirmed.
     */
    @Column(columnDefinition = "TEXT")
    private String finalNote;

    /**
     * Physician's rating of AI draft accuracy.
     */
    @Enumerated(EnumType.STRING)
    @Column
    private NoteAccuracyRating aiAccuracyRating;

    /**
     * Physician's comments on AI draft corrections.
     */
    @Column(length = 1000)
    private String aiCorrectionComments;

    /**
     * Whether physician has confirmed the final note.
     * Once true, note-related fields become immutable.
     */
    @Column(nullable = false)
    private boolean noteConfirmed = false;

    /**
     * Timestamp when note was confirmed.
     */
    @Column
    private Instant noteConfirmedAt;

    /**
     * Staff ID who confirmed the note.
     */
    @Column
    private String noteConfirmedBy;

    // ==================== AI METADATA ====================

    /**
     * AI model version used for draft generation.
     */
    @Column(length = 100)
    private String aiModelVersion;

    /**
     * Prompt template version used.
     */
    @Column(length = 100)
    private String aiPromptVersion;

    /**
     * Timestamp when AI processed the transcript.
     */
    @Column
    private Instant aiProcessedAt;

    // ==================== DIAGNOSES ====================

    /**
     * Diagnoses for this encounter.
     */
    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Diagnosis> diagnoses = new ArrayList<>();

    // ==================== MEDICATION ORDERS ====================

    /**
     * Medication orders for this encounter.
     */
    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MedicationOrder> medicationOrders = new ArrayList<>();

    // ==================== TIMESTAMPS ====================

    /**
     * When the encounter started.
     */
    @Column(nullable = false)
    private Instant startedAt;

    /**
     * When the encounter was completed.
     */
    @Column
    private Instant completedAt;

    /**
     * When prescription was printed.
     */
    @Column
    private Instant prescriptionPrintedAt;

    protected Encounter() {
    }

    /**
     * Creates a new encounter.
     *
     * @param patientId      the patient UUID
     * @param clinicianId    the clinician UUID
     * @param clinicianName  the clinician's display name (snapshot)
     * @param clinicianRole  the clinician's role (snapshot)
     * @param encounterType  the type of encounter
     * @param chiefComplaint the presenting complaint
     * @return a new Encounter in OPEN status
     */
    public static Encounter create(
            UUID patientId,
            UUID clinicianId,
            String clinicianName,
            String clinicianRole,
            EncounterType encounterType,
            String chiefComplaint
    ) {
        Encounter encounter = new Encounter();
        encounter.patientId = patientId;
        encounter.clinicianId = clinicianId;
        encounter.clinicianName = clinicianName;
        encounter.clinicianRole = clinicianRole;
        encounter.encounterType = encounterType;
        encounter.chiefComplaint = chiefComplaint;
        encounter.status = EncounterStatus.OPEN;
        encounter.startedAt = Instant.now();
        return encounter;
    }

    /**
     * Links this encounter to a queue ticket and triage assessment.
     *
     * @param queueTicketId      the queue ticket UUID
     * @param triageAssessmentId the triage assessment UUID (may be null)
     */
    public void linkToQueue(UUID queueTicketId, UUID triageAssessmentId) {
        assertNotCompleted();
        this.queueTicketId = queueTicketId;
        this.triageAssessmentId = triageAssessmentId;
    }

    // ==================== IMMUTABILITY GUARDS ====================

    /**
     * Asserts the encounter is not completed.
     * All mutation operations should call this first.
     *
     * @throws IllegalStateException if encounter is completed or cancelled
     */
    private void assertNotCompleted() {
        if (this.status == EncounterStatus.COMPLETED) {
            throw new IllegalStateException("Cannot modify completed encounter");
        }
        if (this.status == EncounterStatus.CANCELLED) {
            throw new IllegalStateException("Cannot modify cancelled encounter");
        }
    }

    /**
     * Asserts notes have not been confirmed.
     * Documentation operations should call this.
     *
     * @throws IllegalStateException if note is already confirmed
     */
    private void assertNoteNotConfirmed() {
        if (this.noteConfirmed) {
            throw new IllegalStateException("Cannot modify documentation after note confirmation");
        }
    }

    // ==================== CLINICAL NOTES ====================

    /**
     * Records the raw transcript from ambient AI recording.
     *
     * <p><strong>Invariant:</strong> Transcript can only be recorded once.
     * Once recorded, it becomes immutable to preserve AI audit trail.</p>
     *
     * @param transcript the transcribed conversation
     * @throws IllegalStateException    if transcript already exists or encounter is completed
     * @throws IllegalArgumentException if transcript is blank
     */
    public void recordTranscript(String transcript) {
        assertNotCompleted();
        assertNoteNotConfirmed();

        if (this.transcript != null) {
            throw new IllegalStateException("Transcript already recorded. Cannot overwrite.");
        }
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("Transcript cannot be blank");
        }

        this.transcript = transcript;
        this.transcriptRecordedAt = Instant.now();
        this.status = EncounterStatus.DOCUMENTING;
    }

    /**
     * Records AI-generated draft note with metadata.
     *
     * <p><strong>Invariants:</strong>
     * <ul>
     *   <li>Transcript must exist before AI draft can be recorded</li>
     *   <li>AI draft can only be recorded once</li>
     *   <li>Cannot record after note confirmation</li>
     * </ul>
     * </p>
     *
     * @param draftNote     the AI-generated SOAP note
     * @param modelVersion  the AI model version
     * @param promptVersion the prompt template version
     * @throws IllegalStateException    if transcript missing, draft exists, or note confirmed
     * @throws IllegalArgumentException if draftNote is blank
     */
    public void recordAiDraft(String draftNote, String modelVersion, String promptVersion) {
        assertNotCompleted();
        assertNoteNotConfirmed();

        if (this.transcript == null || this.transcript.isBlank()) {
            throw new IllegalStateException("Transcript must exist before AI draft can be recorded");
        }
        if (this.aiDraftNote != null) {
            throw new IllegalStateException("AI draft already recorded. Cannot overwrite.");
        }
        if (draftNote == null || draftNote.isBlank()) {
            throw new IllegalArgumentException("AI draft note cannot be blank");
        }

        this.aiDraftNote = draftNote;
        this.aiModelVersion = modelVersion;
        this.aiPromptVersion = promptVersion;
        this.aiProcessedAt = Instant.now();
    }

    /**
     * Records physician's own authored note.
     *
     * <p>Unlike transcript and AI draft, physician note can be edited
     * until confirmation. Each edit overwrites the previous version.</p>
     *
     * @param note the physician-written note
     * @throws IllegalStateException    if encounter completed or note confirmed
     * @throws IllegalArgumentException if note is blank
     */
    public void recordPhysicianNote(String note) {
        assertNotCompleted();
        assertNoteNotConfirmed();

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Physician note cannot be blank");
        }

        this.physicianAuthoredNote = note;
    }

    /**
     * Confirms the final note with AI accuracy rating.
     *
     * <p><strong>Invariants:</strong>
     * <ul>
     *   <li>Physician-authored note must exist</li>
     *   <li>Can only confirm once (immutable after)</li>
     * </ul>
     * </p>
     *
     * @param finalNote          the final confirmed note
     * @param accuracyRating     physician's rating of AI accuracy
     * @param correctionComments optional comments on corrections
     * @param confirmedBy        staff ID confirming the note
     * @throws IllegalStateException    if physician note missing or already confirmed
     * @throws IllegalArgumentException if finalNote is blank
     */
    public void confirmNote(
            String finalNote,
            NoteAccuracyRating accuracyRating,
            String correctionComments,
            String confirmedBy
    ) {
        assertNotCompleted();

        if (this.noteConfirmed) {
            throw new IllegalStateException("Note already confirmed. Use amendment workflow for changes.");
        }
        if (this.physicianAuthoredNote == null || this.physicianAuthoredNote.isBlank()) {
            throw new IllegalStateException("Physician-authored note must exist before confirmation");
        }
        if (finalNote == null || finalNote.isBlank()) {
            throw new IllegalArgumentException("Final note cannot be blank");
        }

        this.finalNote = finalNote;
        this.aiAccuracyRating = accuracyRating;
        this.aiCorrectionComments = correctionComments;
        this.noteConfirmed = true;
        this.noteConfirmedAt = Instant.now();
        this.noteConfirmedBy = confirmedBy;
    }

    /**
     * Confirms the final note without AI comparison.
     *
     * <p>Use this when no AI draft was generated (manual documentation only).</p>
     *
     * @param finalNote   the final confirmed note
     * @param confirmedBy staff ID confirming the note
     * @throws IllegalStateException    if physician note missing or already confirmed
     * @throws IllegalArgumentException if finalNote is blank
     */
    public void confirmNoteWithoutAi(String finalNote, String confirmedBy) {
        assertNotCompleted();

        if (this.noteConfirmed) {
            throw new IllegalStateException("Note already confirmed. Use amendment workflow for changes.");
        }
        if (this.physicianAuthoredNote == null || this.physicianAuthoredNote.isBlank()) {
            throw new IllegalStateException("Physician-authored note must exist before confirmation");
        }
        if (finalNote == null || finalNote.isBlank()) {
            throw new IllegalArgumentException("Final note cannot be blank");
        }

        this.finalNote = finalNote;
        this.noteConfirmed = true;
        this.noteConfirmedAt = Instant.now();
        this.noteConfirmedBy = confirmedBy;
    }

    /**
     * Checks if AI draft exists for this encounter.
     *
     * @return true if AI draft was generated
     */
    public boolean hasAiDraft() {
        return this.aiDraftNote != null && !this.aiDraftNote.isBlank();
    }

    /**
     * Checks if transcript exists.
     *
     * @return true if transcript was recorded
     */
    public boolean hasTranscript() {
        return this.transcript != null && !this.transcript.isBlank();
    }

    /**
     * Checks if physician note exists.
     *
     * @return true if physician has authored a note
     */
    public boolean hasPhysicianNote() {
        return this.physicianAuthoredNote != null && !this.physicianAuthoredNote.isBlank();
    }

    // ==================== DIAGNOSES ====================

    /**
     * Adds a diagnosis to this encounter.
     *
     * @param diagnosis the diagnosis to add
     * @throws IllegalStateException if encounter is completed
     */
    public void addDiagnosis(Diagnosis diagnosis) {
        assertNotCompleted();
        diagnoses.add(diagnosis);
        diagnosis.setEncounter(this);
    }

    /**
     * Removes a diagnosis from this encounter.
     *
     * @param diagnosis the diagnosis to remove
     * @throws IllegalStateException if encounter is completed
     */
    public void removeDiagnosis(Diagnosis diagnosis) {
        assertNotCompleted();
        diagnoses.remove(diagnosis);
        diagnosis.setEncounter(null);
    }

    /**
     * Checks if encounter has at least one diagnosis.
     *
     * @return true if diagnoses exist
     */
    public boolean hasDiagnosis() {
        return !diagnoses.isEmpty();
    }

    // ==================== MEDICATION ORDERS ====================

    /**
     * Adds a medication order to this encounter.
     *
     * @param order the medication order to add
     * @throws IllegalStateException if encounter is completed
     */
    public void addMedicationOrder(MedicationOrder order) {
        assertNotCompleted();
        medicationOrders.add(order);
        order.setEncounter(this);
    }

    /**
     * Removes a medication order from this encounter.
     *
     * @param order the medication order to remove
     * @throws IllegalStateException if encounter is completed
     */
    public void removeMedicationOrder(MedicationOrder order) {
        assertNotCompleted();
        medicationOrders.remove(order);
        order.setEncounter(null);
    }

    /**
     * Checks if encounter has medication orders.
     *
     * @return true if medication orders exist
     */
    public boolean hasMedicationOrders() {
        return !medicationOrders.isEmpty();
    }

    // ==================== STATUS TRANSITIONS ====================

    /**
     * Marks prescription as printed.
     *
     * @throws IllegalStateException if no medication orders exist or encounter completed
     */
    public void markPrescriptionPrinted() {
        assertNotCompleted();

        if (medicationOrders.isEmpty()) {
            throw new IllegalStateException("Cannot print prescription without medication orders");
        }

        this.prescriptionPrintedAt = Instant.now();
        this.status = EncounterStatus.PENDING_PHARMACY;
    }

    /**
     * Completes the encounter.
     *
     * <p><strong>Invariant:</strong> Note must be confirmed before completion.
     * Once completed, the encounter becomes immutable.</p>
     *
     * @throws IllegalStateException if note is not confirmed or already completed
     */
    public void complete() {
        if (this.status == EncounterStatus.COMPLETED) {
            throw new IllegalStateException("Encounter already completed");
        }
        if (!this.noteConfirmed) {
            throw new IllegalStateException("Cannot complete encounter without confirmed note");
        }

        this.status = EncounterStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /**
     * Cancels the encounter.
     *
     * @throws IllegalStateException if already completed
     */
    public void cancel() {
        if (this.status == EncounterStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel completed encounter");
        }

        this.status = EncounterStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    /**
     * Checks if encounter can be completed.
     *
     * @return true if all completion requirements are met
     */
    public boolean canComplete() {
        return this.noteConfirmed &&
                this.status != EncounterStatus.COMPLETED &&
                this.status != EncounterStatus.CANCELLED;
    }

    /**
     * Checks if encounter is in a terminal state (completed or cancelled).
     *
     * @return true if encounter cannot be modified
     */
    public boolean isTerminal() {
        return this.status == EncounterStatus.COMPLETED ||
                this.status == EncounterStatus.CANCELLED;
    }

    /**
     * Gets completion readiness summary.
     *
     * @return human-readable summary of completion requirements
     */
    public String getCompletionReadiness() {
        if (isTerminal()) {
            return "Encounter is " + status.name().toLowerCase() + ".";
        }

        StringBuilder sb = new StringBuilder();
        if (!noteConfirmed) {
            sb.append("Note not confirmed. ");
        }
        if (diagnoses.isEmpty()) {
            sb.append("No diagnosis recorded (optional). ");
        }
        if (sb.length() == 0) {
            return "Ready for completion.";
        }
        return sb.toString().trim();
    }

    /**
     * Checks if addendums can be added to this encounter.
     * Addendums can only be added to completed encounters.
     *
     * @return true if encounter is completed
     */
    public boolean canHaveAddendum() {
        return this.status == EncounterStatus.COMPLETED;
    }

    /**
     * Asserts that addendums can be added to this encounter.
     *
     * @throws IllegalStateException if encounter is not completed
     */
    public void assertCanHaveAddendum() {
        if (this.status != EncounterStatus.COMPLETED) {
            throw new IllegalStateException("Addendums can only be added to completed encounters. Current status: " + status);
        }
    }

    // ==================== ENUMS ====================

    /**
     * Type of clinical encounter.
     */
    public enum EncounterType {
        /**
         * Initial visit for a new complaint
         */
        NEW_VISIT,
        /**
         * Follow-up for existing condition
         */
        FOLLOW_UP,
        /**
         * Emergency presentation
         */
        EMERGENCY,
        /**
         * Routine checkup
         */
        ROUTINE_CHECKUP,
        /**
         * Procedure or minor surgery
         */
        PROCEDURE
    }

    /**
     * Status of the encounter workflow.
     */
    public enum EncounterStatus {
        /**
         * Encounter started, consultation in progress
         */
        OPEN,
        /**
         * Clinician documenting notes
         */
        DOCUMENTING,
        /**
         * Waiting for pharmacy to dispense
         */
        PENDING_PHARMACY,
        /**
         * Encounter fully completed (immutable)
         */
        COMPLETED,
        /**
         * Encounter cancelled (immutable)
         */
        CANCELLED
    }

    /**
     * Physician's rating of AI-generated note accuracy.
     */
    public enum NoteAccuracyRating {
        /**
         * AI note was fully accurate, no edits needed
         */
        ACCURATE,
        /**
         * AI note had minor errors, small edits made
         */
        MINOR_EDITS,
        /**
         * AI note had significant errors, major rewrite needed
         */
        MAJOR_EDITS,
        /**
         * AI note contained unsafe or incorrect clinical content
         */
        UNSAFE
    }
}