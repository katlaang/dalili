package dalili.com.base.domain.triage;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * Records clinical observations and measurements taken during patient triage.
 *
 * <p>Triage assessment captures vital signs, presenting complaints, and clinical
 * observations that determine the patient's priority in the queue. The assessment
 * follows a structured approach based on the Manchester Triage System (MTS).</p>
 *
 * <p>The system calculates a suggested triage level based on vital signs and
 * chief complaint, but the assessing nurse has full authority to override
 * with documented clinical reasoning.</p>
 *
 * <h3>Vital Signs Captured:</h3>
 * <ul>
 *   <li>Temperature (°C)</li>
 *   <li>Heart rate / Pulse (bpm)</li>
 *   <li>Blood pressure (systolic/diastolic mmHg)</li>
 *   <li>Respiratory rate (breaths/min)</li>
 *   <li>Oxygen saturation (SpO2 %)</li>
 *   <li>Weight (kg) and Height (cm)</li>
 *   <li>Pain score (0-10)</li>
 *   <li>Blood glucose (mmol/L) - if indicated</li>
 * </ul>
 *
 * <h3>Clinical Observations:</h3>
 * <ul>
 *   <li>Level of consciousness (AVPU scale)</li>
 *   <li>Chief complaint and history of present illness</li>
 *   <li>Allergies and current medications</li>
 *   <li>Nursing observations and notes</li>
 * </ul>
 *
 * @see TriageLevel
 * @see ConsciousnessLevel
 */
@Entity
@Table(name = "triage_assessments", indexes = {
        @Index(name = "idx_triage_patient", columnList = "patientId"),
        @Index(name = "idx_triage_queue", columnList = "queueTicketId"),
        @Index(name = "idx_triage_date", columnList = "assessedAt")
})
@Getter
public class TriageAssessment {

    @Id
    @GeneratedValue
    private UUID id;

    /**
     * Reference to the patient record.
     */
    @Column(nullable = false)
    private UUID patientId;

    /**
     * Reference to the queue ticket this assessment is for.
     */
    @Column(nullable = false)
    private UUID queueTicketId;

    /**
     * Patient's date of birth (cached for age-based calculations).
     */
    @Column(nullable = false)
    private LocalDate patientDateOfBirth;

    // ==================== VITAL SIGNS ====================

    /**
     * Body temperature in degrees Celsius.
     * Normal range: 36.1°C - 37.2°C
     */
    @Column(precision = 4, scale = 1)
    private BigDecimal temperatureCelsius;

    /**
     * Heart rate / pulse in beats per minute.
     * Normal adult range: 60-100 bpm
     */
    @Column
    private Integer heartRateBpm;

    /**
     * Systolic blood pressure in mmHg.
     * Normal adult range: 90-120 mmHg
     */
    @Column
    private Integer bloodPressureSystolic;

    /**
     * Diastolic blood pressure in mmHg.
     * Normal adult range: 60-80 mmHg
     */
    @Column
    private Integer bloodPressureDiastolic;

    /**
     * Respiratory rate in breaths per minute.
     * Normal adult range: 12-20 breaths/min
     */
    @Column
    private Integer respiratoryRate;

    /**
     * Oxygen saturation (SpO2) as percentage.
     * Normal range: 95-100%
     */
    @Column
    private Integer oxygenSaturation;

    /**
     * Body weight in kilograms.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal weightKg;

    /**
     * Height in centimeters.
     */
    @Column(precision = 5, scale = 1)
    private BigDecimal heightCm;

    /**
     * Pain score on 0-10 scale.
     * 0 = no pain, 10 = worst pain imaginable
     */
    @Column
    private Integer painScore;

    /**
     * Blood glucose level in mmol/L (if measured).
     * Normal fasting range: 4.0-5.4 mmol/L
     */
    @Column(precision = 4, scale = 1)
    private BigDecimal bloodGlucoseMmol;

    // ==================== CLINICAL OBSERVATIONS ====================

    /**
     * Level of consciousness using AVPU scale.
     */
    @Enumerated(EnumType.STRING)
    @Column
    private ConsciousnessLevel consciousnessLevel;

    /**
     * Primary presenting complaint in patient's words.
     */
    @Column(length = 500, nullable = false)
    private String chiefComplaint;

    /**
     * History of present illness - onset, duration, severity, associated symptoms.
     */
    @Column(length = 2000)
    private String historyOfPresentIllness;

    /**
     * Known allergies (medications, food, environmental).
     */
    @Column(length = 500)
    private String allergies;

    /**
     * Current medications the patient is taking.
     */
    @Column(length = 1000)
    private String currentMedications;

    /**
     * Relevant past medical history.
     */
    @Column(length = 1000)
    private String pastMedicalHistory;

    /**
     * Free-form nursing observations and notes.
     */
    @Column(length = 2000)
    private String nursingNotes;

    /**
     * Emergency contact name captured during triage.
     */
    @Column(length = 255)
    private String emergencyContactName;

    /**
     * Emergency contact phone captured during triage.
     */
    @Column(length = 100)
    private String emergencyContactPhone;

    // ==================== TRIAGE CLASSIFICATION ====================

    /**
     * System-calculated triage level based on vital signs and complaints.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriageLevel systemTriageLevel;

    /**
     * Final triage level after nurse review (may differ from system suggestion).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriageLevel finalTriageLevel;

    /**
     * Whether the nurse overrode the system's triage suggestion.
     */
    @Column(nullable = false)
    private boolean triageOverridden = false;

    /**
     * Clinical reasoning for triage override (required if overridden).
     */
    @Column(length = 1000)
    private String overrideReason;

    // ==================== RED FLAG INDICATORS ====================

    /**
     * Chest pain or discomfort present.
     */
    @Column(nullable = false)
    private boolean chestPain = false;

    /**
     * Difficulty breathing or shortness of breath.
     */
    @Column(nullable = false)
    private boolean difficultyBreathing = false;

    /**
     * Signs of stroke (facial drooping, arm weakness, speech difficulty).
     */
    @Column(nullable = false)
    private boolean strokeSymptoms = false;

    /**
     * Severe or uncontrolled bleeding.
     */
    @Column(nullable = false)
    private boolean severebleeding = false;

    /**
     * Signs of severe allergic reaction (anaphylaxis).
     */
    @Column(nullable = false)
    private boolean allergicReaction = false;

    /**
     * Altered mental status or confusion.
     */
    @Column(nullable = false)
    private boolean alteredMentalStatus = false;

    /**
     * Pregnant patient with concerning symptoms.
     */
    @Column(nullable = false)
    private boolean pregnancyConcern = false;

    /**
     * Severe abdominal pain.
     */
    @Column(nullable = false)
    private boolean severeAbdominalPain = false;

    // ==================== METADATA ====================

    /**
     * Timestamp when assessment was performed.
     */
    @Column(nullable = false)
    private Instant assessedAt;

    /**
     * Staff ID of the nurse who performed the assessment.
     */
    @Column(nullable = false)
    private String assessedByStaffId;

    /**
     * Staff name of the nurse (for display).
     */
    @Column(nullable = false)
    private String assessedByStaffName;

    /**
     * Timestamp of last modification.
     */
    @Column
    private Instant lastModifiedAt;

    /**
     * Staff ID who last modified the assessment.
     */
    @Column
    private String lastModifiedByStaffId;

    /**
     * Indicates if this is a reassessment of a waiting patient.
     */
    @Column(nullable = false)
    private boolean isReassessment = false;

    /**
     * Reference to previous assessment if this is a reassessment.
     */
    @Column
    private UUID previousAssessmentId;

    protected TriageAssessment() {
    }

    /**
     * Creates a new triage assessment for a patient.
     *
     * @param patientId          the patient's UUID
     * @param queueTicketId      the queue ticket this assessment is for
     * @param patientDateOfBirth patient's DOB for age calculations
     * @param chiefComplaint     the presenting complaint
     * @param staffId            ID of assessing nurse
     * @param staffName          name of assessing nurse
     * @return a new TriageAssessment
     */
    public static TriageAssessment create(
            UUID patientId,
            UUID queueTicketId,
            LocalDate patientDateOfBirth,
            String chiefComplaint,
            String staffId,
            String staffName
    ) {
        TriageAssessment assessment = new TriageAssessment();
        assessment.patientId = patientId;
        assessment.queueTicketId = queueTicketId;
        assessment.patientDateOfBirth = patientDateOfBirth;
        assessment.chiefComplaint = chiefComplaint;
        assessment.assessedAt = Instant.now();
        assessment.assessedByStaffId = staffId;
        assessment.assessedByStaffName = staffName;
        assessment.systemTriageLevel = TriageLevel.GREEN; // Default, will be calculated
        assessment.finalTriageLevel = TriageLevel.GREEN;
        return assessment;
    }

    /**
     * Creates a reassessment based on a previous assessment.
     */
    public static TriageAssessment createReassessment(
            TriageAssessment previous,
            String staffId,
            String staffName
    ) {
        TriageAssessment assessment = new TriageAssessment();
        assessment.patientId = previous.patientId;
        assessment.queueTicketId = previous.queueTicketId;
        assessment.patientDateOfBirth = previous.patientDateOfBirth;
        assessment.chiefComplaint = previous.chiefComplaint;
        assessment.assessedAt = Instant.now();
        assessment.assessedByStaffId = staffId;
        assessment.assessedByStaffName = staffName;
        assessment.isReassessment = true;
        assessment.previousAssessmentId = previous.id;
        assessment.systemTriageLevel = TriageLevel.GREEN;
        assessment.finalTriageLevel = TriageLevel.GREEN;
        return assessment;
    }

    // ==================== VITAL SIGN SETTERS ====================

    /**
     * Records temperature measurement.
     *
     * @param celsius temperature in degrees Celsius
     */
    public void recordTemperature(BigDecimal celsius) {
        this.temperatureCelsius = celsius;
    }

    /**
     * Records heart rate measurement.
     *
     * @param bpm heart rate in beats per minute
     */
    public void recordHeartRate(Integer bpm) {
        this.heartRateBpm = bpm;
    }

    /**
     * Records blood pressure measurement.
     *
     * @param systolic  systolic pressure in mmHg
     * @param diastolic diastolic pressure in mmHg
     */
    public void recordBloodPressure(Integer systolic, Integer diastolic) {
        this.bloodPressureSystolic = systolic;
        this.bloodPressureDiastolic = diastolic;
    }

    /**
     * Records respiratory rate measurement.
     *
     * @param breathsPerMinute respiratory rate
     */
    public void recordRespiratoryRate(Integer breathsPerMinute) {
        this.respiratoryRate = breathsPerMinute;
    }

    /**
     * Records oxygen saturation measurement.
     *
     * @param spO2Percent oxygen saturation percentage
     */
    public void recordOxygenSaturation(Integer spO2Percent) {
        this.oxygenSaturation = spO2Percent;
    }

    /**
     * Records weight and height measurements.
     *
     * @param weightKg weight in kilograms
     * @param heightCm height in centimeters
     */
    public void recordBodyMeasurements(BigDecimal weightKg, BigDecimal heightCm) {
        this.weightKg = weightKg;
        this.heightCm = heightCm;
    }

    /**
     * Records pain score.
     *
     * @param score pain level 0-10
     */
    public void recordPainScore(Integer score) {
        if (score != null && (score < 0 || score > 10)) {
            throw new IllegalArgumentException("Pain score must be between 0 and 10");
        }
        this.painScore = score;
    }

    /**
     * Records blood glucose measurement.
     *
     * @param mmolL glucose level in mmol/L
     */
    public void recordBloodGlucose(BigDecimal mmolL) {
        this.bloodGlucoseMmol = mmolL;
    }

    /**
     * Records level of consciousness.
     *
     * @param level AVPU consciousness level
     */
    public void recordConsciousnessLevel(ConsciousnessLevel level) {
        this.consciousnessLevel = level;
    }

    // ==================== CLINICAL OBSERVATION SETTERS ====================

    /**
     * Records history of present illness.
     *
     * @param history description of illness onset, duration, severity
     */
    public void recordHistoryOfPresentIllness(String history) {
        this.historyOfPresentIllness = history;
    }

    /**
     * Records patient allergies.
     *
     * @param allergies known allergies
     */
    public void recordAllergies(String allergies) {
        this.allergies = allergies;
    }

    /**
     * Records current medications.
     *
     * @param medications list of current medications
     */
    public void recordCurrentMedications(String medications) {
        this.currentMedications = medications;
    }

    /**
     * Records past medical history.
     *
     * @param history relevant medical history
     */
    public void recordPastMedicalHistory(String history) {
        this.pastMedicalHistory = history;
    }

    /**
     * Records nursing notes and observations.
     *
     * @param notes free-form nursing notes
     */
    public void recordNursingNotes(String notes) {
        this.nursingNotes = notes;
    }

    /**
     * Records emergency contact details.
     */
    public void recordEmergencyContact(String name, String phone) {
        this.emergencyContactName = name;
        this.emergencyContactPhone = phone;
    }

    // ==================== RED FLAG SETTERS ====================

    /**
     * Records red flag indicators for rapid triage.
     */
    public void recordRedFlags(
            boolean chestPain,
            boolean difficultyBreathing,
            boolean strokeSymptoms,
            boolean severebleeding,
            boolean allergicReaction,
            boolean alteredMentalStatus,
            boolean pregnancyConcern,
            boolean severeAbdominalPain
    ) {
        this.chestPain = chestPain;
        this.difficultyBreathing = difficultyBreathing;
        this.strokeSymptoms = strokeSymptoms;
        this.severebleeding = severebleeding;
        this.allergicReaction = allergicReaction;
        this.alteredMentalStatus = alteredMentalStatus;
        this.pregnancyConcern = pregnancyConcern;
        this.severeAbdominalPain = severeAbdominalPain;
    }

    // ==================== TRIAGE CLASSIFICATION ====================

    /**
     * Sets the system-calculated triage level.
     * Called by TriageCalculator after analyzing vitals.
     *
     * @param level the calculated triage level
     */
    public void setSystemTriageLevel(TriageLevel level) {
        this.systemTriageLevel = level;
        // If not yet overridden, final level follows system
        if (!this.triageOverridden) {
            this.finalTriageLevel = level;
        }
    }

    /**
     * Accepts the system's triage recommendation without override.
     */
    public void acceptSystemTriage() {
        this.finalTriageLevel = this.systemTriageLevel;
        this.triageOverridden = false;
        this.overrideReason = null;
    }

    /**
     * Overrides the system's triage recommendation with clinical judgment.
     *
     * @param newLevel the nurse-determined triage level
     * @param reason   clinical reasoning for the override (required)
     * @param staffId  ID of staff making the override
     * @throws IllegalArgumentException if reason is blank
     */
    public void overrideTriage(TriageLevel newLevel, String reason, String staffId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Override reason is required");
        }
        this.finalTriageLevel = newLevel;
        this.triageOverridden = true;
        this.overrideReason = reason;
        this.lastModifiedAt = Instant.now();
        this.lastModifiedByStaffId = staffId;
    }

    // ==================== CALCULATED PROPERTIES ====================

    /**
     * Calculates patient age in years.
     *
     * @return age in years
     */
    public int getPatientAgeYears() {
        return Period.between(patientDateOfBirth, LocalDate.now()).getYears();
    }

    /**
     * Calculates BMI if weight and height are recorded.
     *
     * @return BMI or null if measurements not available
     */
    public BigDecimal getBmi() {
        if (weightKg == null || heightCm == null || heightCm.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        // BMI = weight(kg) / height(m)²
        BigDecimal heightM = heightCm.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        return weightKg.divide(heightM.multiply(heightM), 1, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Calculates Mean Arterial Pressure (MAP) if blood pressure is recorded.
     * MAP = (SBP + 2*DBP) / 3
     *
     * @return MAP in mmHg or null if BP not available
     */
    public Integer getMeanArterialPressure() {
        if (bloodPressureSystolic == null || bloodPressureDiastolic == null) {
            return null;
        }
        return (bloodPressureSystolic + 2 * bloodPressureDiastolic) / 3;
    }

    /**
     * Checks if any red flags are present.
     *
     * @return true if any red flag is set
     */
    public boolean hasRedFlags() {
        return chestPain || difficultyBreathing || strokeSymptoms || severebleeding ||
                allergicReaction || alteredMentalStatus || pregnancyConcern || severeAbdominalPain;
    }

    /**
     * Checks if patient is pediatric (under 18).
     *
     * @return true if patient is under 18
     */
    public boolean isPediatric() {
        return getPatientAgeYears() < 18;
    }

    /**
     * Checks if patient is elderly (65 or older).
     *
     * @return true if patient is 65 or older
     */
    public boolean isElderly() {
        return getPatientAgeYears() >= 65;
    }

    /**
     * AVPU scale for level of consciousness assessment.
     */
    public enum ConsciousnessLevel {
        /**
         * Alert - patient is fully awake and responsive.
         */
        ALERT,

        /**
         * Voice - patient responds to verbal stimuli.
         */
        VOICE,

        /**
         * Pain - patient responds only to painful stimuli.
         */
        PAIN,

        /**
         * Unresponsive - patient does not respond to any stimuli.
         */
        UNRESPONSIVE
    }
}
