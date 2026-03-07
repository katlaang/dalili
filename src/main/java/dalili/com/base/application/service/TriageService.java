package dalili.com.base.application.service;


import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.domain.triage.TriageCalculator;
import dalili.com.base.domain.triage.TriageLevel;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import dalili.com.base.repository.triage.TriageAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing triage assessments.
 *
 * <p>This service handles the complete triage workflow:
 * <ul>
 *   <li>Creating new triage assessments</li>
 *   <li>Recording vital signs and clinical observations</li>
 *   <li>Calculating suggested triage levels</li>
 *   <li>Processing nurse overrides with documentation</li>
 *   <li>Updating queue priorities based on triage results</li>
 *   <li>Performing reassessments for waiting patients</li>
 * </ul>
 * </p>
 *
 * <p>Triage is the clinical assessment process where a nurse evaluates
 * the patient's condition and assigns a priority level. This service
 * integrates with {@link QueueService} to update queue position based
 * on triage results.</p>
 *
 * <p>All triage actions are audited for clinical governance and quality review.</p>
 *
 * @see TriageAssessment
 * @see TriageCalculator
 * @see QueueService
 */
@Service
public class TriageService {

    private final TriageAssessmentRepository triageRepository;
    private final QueueTicketRepository queueRepository;
    private final PatientService patientService;
    private final QueueService queueService;
    private final TriageCalculator triageCalculator;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    /**
     * Constructs a new TriageService with required dependencies.
     *
     * @param triageRepository repository for triage assessment persistence
     * @param queueRepository  repository for queue ticket access
     * @param patientService   service for patient data access
     * @param queueService     service for queue priority updates
     * @param triageCalculator calculator for suggested triage levels
     * @param auditService     service for audit trail recording
     * @param auditGuard       guard for session validation
     * @param sessionContext   current session context
     */
    public TriageService(
            TriageAssessmentRepository triageRepository,
            QueueTicketRepository queueRepository,
            PatientService patientService,
            QueueService queueService,
            TriageCalculator triageCalculator,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.triageRepository = triageRepository;
        this.queueRepository = queueRepository;
        this.patientService = patientService;
        this.queueService = queueService;
        this.triageCalculator = triageCalculator;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    // ==================== ASSESSMENT CREATION ====================

    /**
     * Begins a new triage assessment for a patient in the queue.
     *
     * <p>This creates an initial assessment record linked to the queue ticket.
     * The assessment captures the patient's presenting complaint and prepares
     * for vital sign recording.</p>
     *
     * @param queueTicketId  the queue ticket UUID
     * @param chiefComplaint the patient's presenting complaint
     * @return the new triage assessment
     * @throws TriageException if ticket not found or patient lookup fails
     */
    @Transactional
    public TriageAssessment beginAssessment(UUID queueTicketId, String chiefComplaint) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueRepository.findById(queueTicketId)
                .orElseThrow(() -> new TriageException("Queue ticket not found"));

        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(ticket.getPatientId()));
        if (patientOpt.isEmpty()) {
            throw new TriageException("Patient not found");
        }
        Patient patient = patientOpt.get();

        String staffId = sessionContext.username();
        String staffName = sessionContext.username();

        TriageAssessment assessment = TriageAssessment.create(
                patient.getId(),
                queueTicketId,
                patient.getDateOfBirth(),
                chiefComplaint,
                staffId,
                staffName
        );

        assessment = triageRepository.save(assessment);

        auditService.record("TRIAGE_STARTED", patient.getId(),
                String.format("Triage assessment started for ticket %s", ticket.getTicketNumber()));

        return assessment;
    }

    /**
     * Creates a reassessment for a waiting patient whose condition may have changed.
     *
     * <p>Reassessment is performed when a patient's condition appears to be
     * deteriorating while waiting. The new assessment links to the previous one
     * for continuity of care documentation.</p>
     *
     * @param queueTicketId the queue ticket UUID
     * @return the new reassessment
     * @throws TriageException if no previous assessment exists
     */
    @Transactional
    public TriageAssessment beginReassessment(UUID queueTicketId) {
        auditGuard.assertSessionActive();

        TriageAssessment previous = triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId)
                .orElseThrow(() -> new TriageException("No previous assessment found"));

        String staffId = sessionContext.username();
        String staffName = sessionContext.username();

        TriageAssessment reassessment = TriageAssessment.createReassessment(previous, staffId, staffName);
        reassessment = triageRepository.save(reassessment);

        auditService.record("TRIAGE_REASSESSMENT_STARTED", previous.getPatientId(),
                "Reassessment started for waiting patient");

        return reassessment;
    }

    // ==================== VITAL SIGNS RECORDING ====================

    /**
     * Records vital signs for an assessment.
     *
     * <p>After recording vitals, the system automatically calculates a suggested
     * triage level based on the readings. The nurse can then accept or override
     * this suggestion.</p>
     *
     * @param assessmentId the assessment UUID
     * @param vitals       the vital signs data
     * @return the updated assessment with system triage suggestion
     * @throws TriageException if assessment not found
     */
    @Transactional
    public TriageAssessment recordVitals(UUID assessmentId, VitalsInput vitals) {
        auditGuard.assertSessionActive();

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        // Record all vital signs
        if (vitals.temperatureCelsius() != null) {
            assessment.recordTemperature(vitals.temperatureCelsius());
        }
        if (vitals.heartRateBpm() != null) {
            assessment.recordHeartRate(vitals.heartRateBpm());
        }
        if (vitals.bloodPressureSystolic() != null && vitals.bloodPressureDiastolic() != null) {
            assessment.recordBloodPressure(vitals.bloodPressureSystolic(), vitals.bloodPressureDiastolic());
        }
        if (vitals.respiratoryRate() != null) {
            assessment.recordRespiratoryRate(vitals.respiratoryRate());
        }
        if (vitals.oxygenSaturation() != null) {
            assessment.recordOxygenSaturation(vitals.oxygenSaturation());
        }
        if (vitals.weightKg() != null || vitals.heightCm() != null) {
            assessment.recordBodyMeasurements(vitals.weightKg(), vitals.heightCm());
        }
        if (vitals.painScore() != null) {
            assessment.recordPainScore(vitals.painScore());
        }
        if (vitals.bloodGlucoseMmol() != null) {
            assessment.recordBloodGlucose(vitals.bloodGlucoseMmol());
        }
        if (vitals.consciousnessLevel() != null) {
            assessment.recordConsciousnessLevel(vitals.consciousnessLevel());
        }

        // Calculate and set system suggested triage
        TriageLevel suggested = triageCalculator.calculate(assessment);
        assessment.setSystemTriageLevel(suggested);

        assessment = triageRepository.save(assessment);

        auditService.record("TRIAGE_VITALS_RECORDED", assessment.getPatientId(),
                String.format("Vitals recorded. System suggests: %s", suggested));

        return assessment;
    }

    /**
     * Records red flag indicators for rapid triage.
     *
     * <p>Red flags are critical symptoms that may indicate life-threatening
     * conditions. Recording these triggers immediate recalculation of the
     * suggested triage level.</p>
     *
     * @param assessmentId the assessment UUID
     * @param redFlags     the red flag data
     * @return the updated assessment
     * @throws TriageException if assessment not found
     */
    @Transactional
    public TriageAssessment recordRedFlags(UUID assessmentId, RedFlagsInput redFlags) {
        auditGuard.assertSessionActive();

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        assessment.recordRedFlags(
                redFlags.chestPain(),
                redFlags.difficultyBreathing(),
                redFlags.strokeSymptoms(),
                redFlags.severebleeding(),
                redFlags.allergicReaction(),
                redFlags.alteredMentalStatus(),
                redFlags.pregnancyConcern(),
                redFlags.severeAbdominalPain()
        );

        // Recalculate triage with red flags
        TriageLevel suggested = triageCalculator.calculate(assessment);
        assessment.setSystemTriageLevel(suggested);

        assessment = triageRepository.save(assessment);

        if (assessment.hasRedFlags()) {
            auditService.record("TRIAGE_RED_FLAGS", assessment.getPatientId(),
                    "Red flags identified: " + triageCalculator.generateTriageSummary(assessment));
        }

        return assessment;
    }

    /**
     * Records clinical observations and patient history.
     *
     * <p>This includes history of present illness, allergies, medications,
     * past medical history, and nursing notes. These observations provide
     * context for clinical decision-making.</p>
     *
     * @param assessmentId the assessment UUID
     * @param observations the clinical observation data
     * @return the updated assessment
     * @throws TriageException if assessment not found
     */
    @Transactional
    public TriageAssessment recordClinicalObservations(UUID assessmentId, ClinicalObservationsInput observations) {
        auditGuard.assertSessionActive();

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        if (observations.historyOfPresentIllness() != null) {
            assessment.recordHistoryOfPresentIllness(observations.historyOfPresentIllness());
        }
        if (observations.allergies() != null) {
            assessment.recordAllergies(observations.allergies());
        }
        if (observations.currentMedications() != null) {
            assessment.recordCurrentMedications(observations.currentMedications());
        }
        if (observations.pastMedicalHistory() != null) {
            assessment.recordPastMedicalHistory(observations.pastMedicalHistory());
        }
        if (observations.nursingNotes() != null) {
            assessment.recordNursingNotes(observations.nursingNotes());
        }

        assessment = triageRepository.save(assessment);

        auditService.record("TRIAGE_OBSERVATIONS_RECORDED", assessment.getPatientId(),
                "Clinical observations recorded");

        return assessment;
    }

    // ==================== TRIAGE FINALIZATION ====================

    /**
     * Accepts the system's triage recommendation and finalizes the assessment.
     *
     * <p>This method completes the triage process by accepting the system-calculated
     * triage level. It updates the queue ticket priority accordingly.</p>
     *
     * @param assessmentId the assessment UUID
     * @return the finalized assessment
     * @throws TriageException if assessment not found
     */
    @Transactional
    public TriageAssessment acceptSystemTriage(UUID assessmentId) {
        auditGuard.assertSessionActive();

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        assessment.acceptSystemTriage();
        assessment = triageRepository.save(assessment);

        // Update queue priority
        updateQueueAfterTriage(assessment);

        auditService.record("TRIAGE_ACCEPTED", assessment.getPatientId(),
                String.format("System triage accepted: %s", assessment.getFinalTriageLevel()));

        return assessment;
    }

    /**
     * Overrides the system's triage recommendation with clinical judgment.
     *
     * <p>Nurses may override the system recommendation when their clinical
     * assessment differs. A documented reason is required for all overrides
     * to support quality review and continuous improvement of the triage
     * algorithm.</p>
     *
     * @param assessmentId the assessment UUID
     * @param newLevel     the nurse-determined triage level
     * @param reason       clinical reasoning for the override (required)
     * @return the finalized assessment
     * @throws TriageException if reason is not provided or assessment not found
     */
    @Transactional
    public TriageAssessment overrideTriage(UUID assessmentId, TriageLevel newLevel, String reason) {
        auditGuard.assertSessionActive();

        if (reason == null || reason.isBlank()) {
            throw new TriageException("Override reason is required");
        }

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        TriageLevel systemLevel = assessment.getSystemTriageLevel();
        String staffId = sessionContext.username();

        assessment.overrideTriage(newLevel, reason, staffId);
        assessment = triageRepository.save(assessment);

        // Update queue priority
        updateQueueAfterTriage(assessment);

        auditService.record("TRIAGE_OVERRIDDEN", assessment.getPatientId(),
                String.format("Triage overridden: %s → %s. Reason: %s",
                        systemLevel, newLevel, reason));

        return assessment;
    }

    // ==================== RETRIEVAL ====================

    /**
     * Retrieves an assessment by ID.
     *
     * @param assessmentId the assessment UUID
     * @return the assessment if found
     */
    public Optional<TriageAssessment> findById(UUID assessmentId) {
        return triageRepository.findById(assessmentId);
    }

    /**
     * Retrieves the current assessment for a queue ticket.
     *
     * @param queueTicketId the queue ticket UUID
     * @return the most recent assessment
     * @throws TriageException if no assessment found
     */
    public TriageAssessment getAssessmentForTicket(UUID queueTicketId) {
        return triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId)
                .orElseThrow(() -> new TriageException("No assessment found for ticket"));
    }

    /**
     * Retrieves the current assessment for a queue ticket if it exists.
     *
     * @param queueTicketId the queue ticket UUID
     * @return Optional containing the assessment if found
     */
    public Optional<TriageAssessment> findAssessmentForTicket(UUID queueTicketId) {
        return triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId);
    }

    /**
     * Retrieves all assessments for a queue ticket (including reassessments).
     *
     * @param queueTicketId the queue ticket UUID
     * @return list of assessments ordered by time descending
     */
    public List<TriageAssessment> getAssessmentHistoryForTicket(UUID queueTicketId) {
        return triageRepository.findByQueueTicketIdOrderByAssessedAtDesc(queueTicketId);
    }

    /**
     * Retrieves all assessments for a patient.
     *
     * @param patientId the patient UUID
     * @return list of assessments ordered by time descending
     */
    public List<TriageAssessment> getAssessmentHistoryForPatient(UUID patientId) {
        return triageRepository.findByPatientIdOrderByAssessedAtDesc(patientId);
    }

    /**
     * Retrieves the system's triage recommendation summary.
     *
     * <p>This summary explains the clinical factors that influenced
     * the system's triage calculation.</p>
     *
     * @param assessmentId the assessment UUID
     * @return summary explaining the triage calculation
     * @throws TriageException if assessment not found
     */
    public String getTriageSummary(UUID assessmentId) {
        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        return triageCalculator.generateTriageSummary(assessment);
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Updates the queue ticket after triage is finalized.
     *
     * <p>This method updates the queue ticket's triage level and
     * links it to the assessment record.</p>
     */
    private void updateQueueAfterTriage(TriageAssessment assessment) {
        queueService.updateTriageLevel(
                assessment.getQueueTicketId(),
                assessment.getFinalTriageLevel(),
                assessment.getId()
        );
    }

    // ==================== INPUT RECORDS ====================

    /**
     * Input data for vital signs recording.
     *
     * @param temperatureCelsius     body temperature in degrees Celsius
     * @param heartRateBpm           heart rate in beats per minute
     * @param bloodPressureSystolic  systolic blood pressure in mmHg
     * @param bloodPressureDiastolic diastolic blood pressure in mmHg
     * @param respiratoryRate        respiratory rate in breaths per minute
     * @param oxygenSaturation       oxygen saturation (SpO2) percentage
     * @param weightKg               body weight in kilograms
     * @param heightCm               height in centimeters
     * @param painScore              pain level on 0-10 scale
     * @param bloodGlucoseMmol       blood glucose in mmol/L
     * @param consciousnessLevel     AVPU consciousness level
     */
    public record VitalsInput(
            BigDecimal temperatureCelsius,
            Integer heartRateBpm,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer respiratoryRate,
            Integer oxygenSaturation,
            BigDecimal weightKg,
            BigDecimal heightCm,
            Integer painScore,
            BigDecimal bloodGlucoseMmol,
            TriageAssessment.ConsciousnessLevel consciousnessLevel
    ) {
    }

    /**
     * Input data for red flag indicators.
     *
     * @param chestPain           chest pain or discomfort present
     * @param difficultyBreathing difficulty breathing or shortness of breath
     * @param strokeSymptoms      signs of stroke (FAST positive)
     * @param severebleeding      severe or uncontrolled bleeding
     * @param allergicReaction    signs of severe allergic reaction
     * @param alteredMentalStatus altered mental status or confusion
     * @param pregnancyConcern    pregnant patient with concerning symptoms
     * @param severeAbdominalPain severe abdominal pain
     */
    public record RedFlagsInput(
            boolean chestPain,
            boolean difficultyBreathing,
            boolean strokeSymptoms,
            boolean severebleeding,
            boolean allergicReaction,
            boolean alteredMentalStatus,
            boolean pregnancyConcern,
            boolean severeAbdominalPain
    ) {
    }

    /**
     * Input data for clinical observations.
     *
     * @param historyOfPresentIllness description of illness onset, duration, severity
     * @param allergies               known allergies (medications, food, environmental)
     * @param currentMedications      list of current medications
     * @param pastMedicalHistory      relevant past medical history
     * @param nursingNotes            free-form nursing observations and notes
     */
    public record ClinicalObservationsInput(
            String historyOfPresentIllness,
            String allergies,
            String currentMedications,
            String pastMedicalHistory,
            String nursingNotes
    ) {
    }

    /**
     * Exception thrown for triage-related errors.
     */
    public static class TriageException extends RuntimeException {
        /**
         * Creates a new TriageException with the specified message.
         *
         * @param message the error message
         */
        public TriageException(String message) {
            super(message);
        }
    }
}