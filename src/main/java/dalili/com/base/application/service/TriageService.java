package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.escalation.EscalationResponse;
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
    private final PolicyEscalationService policyEscalationService;

    public TriageService(
            TriageAssessmentRepository triageRepository,
            QueueTicketRepository queueRepository,
            PatientService patientService,
            QueueService queueService,
            TriageCalculator triageCalculator,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext,
            PolicyEscalationService policyEscalationService
    ) {
        this.triageRepository = triageRepository;
        this.queueRepository = queueRepository;
        this.patientService = patientService;
        this.queueService = queueService;
        this.triageCalculator = triageCalculator;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
        this.policyEscalationService = policyEscalationService;
    }

    // ==================== ASSESSMENT CREATION ====================

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

        // Policy engine evaluation - may escalate to emergency
        EscalationResponse escalation = policyEscalationService.evaluate(assessment);
        if (escalation.isEmergency()) {
            assessment.setSystemTriageLevel(TriageLevel.RED);
            auditService.record("TRIAGE_ESCALATED", assessment.getPatientId(),
                    String.format("Policy engine escalated to RED. Incident: %s",
                            escalation.incidentId()));
        }

        assessment = triageRepository.save(assessment);

        auditService.record("TRIAGE_VITALS_RECORDED", assessment.getPatientId(),
                String.format("Vitals recorded. System suggests: %s", assessment.getSystemTriageLevel()));

        return assessment;
    }

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

        // Policy engine evaluation - may escalate to emergency
        EscalationResponse escalation = policyEscalationService.evaluate(assessment);
        if (escalation.isEmergency()) {
            assessment.setSystemTriageLevel(TriageLevel.RED);
            auditService.record("TRIAGE_ESCALATED", assessment.getPatientId(),
                    String.format("Policy engine escalated to RED. Incident: %s",
                            escalation.incidentId()));
        }

        assessment = triageRepository.save(assessment);

        if (assessment.hasRedFlags()) {
            auditService.record("TRIAGE_RED_FLAGS", assessment.getPatientId(),
                    "Red flags identified: " + triageCalculator.generateTriageSummary(assessment));
        }

        return assessment;
    }

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

    @Transactional
    public TriageAssessment acceptSystemTriage(UUID assessmentId) {
        auditGuard.assertSessionActive();

        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));

        assessment.acceptSystemTriage();
        assessment = triageRepository.save(assessment);

        updateQueueAfterTriage(assessment);

        auditService.record("TRIAGE_ACCEPTED", assessment.getPatientId(),
                String.format("System triage accepted: %s", assessment.getFinalTriageLevel()));

        return assessment;
    }

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

        updateQueueAfterTriage(assessment);

        auditService.record("TRIAGE_OVERRIDDEN", assessment.getPatientId(),
                String.format("Triage overridden: %s → %s. Reason: %s",
                        systemLevel, newLevel, reason));

        return assessment;
    }

    // ==================== RETRIEVAL ====================

    public Optional<TriageAssessment> findById(UUID assessmentId) {
        return triageRepository.findById(assessmentId);
    }

    public TriageAssessment getAssessmentForTicket(UUID queueTicketId) {
        return triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId)
                .orElseThrow(() -> new TriageException("No assessment found for ticket"));
    }

    public Optional<TriageAssessment> findAssessmentForTicket(UUID queueTicketId) {
        return triageRepository.findTopByQueueTicketIdOrderByAssessedAtDesc(queueTicketId);
    }

    public List<TriageAssessment> getAssessmentHistoryForTicket(UUID queueTicketId) {
        return triageRepository.findByQueueTicketIdOrderByAssessedAtDesc(queueTicketId);
    }

    public List<TriageAssessment> getAssessmentHistoryForPatient(UUID patientId) {
        return triageRepository.findByPatientIdOrderByAssessedAtDesc(patientId);
    }

    public String getTriageSummary(UUID assessmentId) {
        TriageAssessment assessment = triageRepository.findById(assessmentId)
                .orElseThrow(() -> new TriageException("Assessment not found"));
        return triageCalculator.generateTriageSummary(assessment);
    }

    // ==================== PRIVATE HELPERS ====================

    private void updateQueueAfterTriage(TriageAssessment assessment) {
        queueService.updateTriageLevel(
                assessment.getQueueTicketId(),
                assessment.getFinalTriageLevel(),
                assessment.getId()
        );
    }

    // ==================== INPUT RECORDS ====================

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

    public record ClinicalObservationsInput(
            String historyOfPresentIllness,
            String allergies,
            String currentMedications,
            String pastMedicalHistory,
            String nursingNotes
    ) {
    }

    public static class TriageException extends RuntimeException {
        public TriageException(String message) {
            super(message);
        }
    }
}