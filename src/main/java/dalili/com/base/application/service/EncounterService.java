package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.ai.ConnectivityMonitor;
import dalili.com.base.domain.ai.NoteComparisonService;
import dalili.com.base.domain.encounter.model.Diagnosis;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.model.EncounterPreview;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.encounter.repository.MedicationOrderRepository;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import dalili.com.base.repository.triage.TriageAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Service for managing clinical encounters.
 *
 * <p>This service handles the complete encounter workflow:
 * <ul>
 *   <li>Pre-encounter preview for physicians</li>
 *   <li>Creating encounters from queue tickets</li>
 *   <li>Recording clinical notes (transcript, AI draft, physician note)</li>
 *   <li>Managing diagnoses and medication orders</li>
 *   <li>Generating printable prescriptions</li>
 * </ul>
 * </p>
 *
 * <h3>Safety Invariants Enforced:</h3>
 * <ul>
 *   <li>Transcript can only be recorded once</li>
 *   <li>AI draft requires transcript and can only be recorded once</li>
 *   <li>Note confirmation requires physician note and can only happen once</li>
 *   <li>Completed encounters are immutable</li>
 * </ul>
 *
 * @see Encounter
 * @see EncounterPreview
 */
@Service
public class EncounterService {

    private static final Logger log = LoggerFactory.getLogger(EncounterService.class);

    private final EncounterRepository encounterRepository;
    private final MedicationOrderRepository medicationOrderRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final TriageAssessmentRepository triageAssessmentRepository;
    private final PatientService patientService;
    private final QueueService queueService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;
    private final ConnectivityMonitor connectivityMonitor;
    private final NoteComparisonService noteComparisonService;
    private final String clinicName;

    public EncounterService(
            EncounterRepository encounterRepository,
            MedicationOrderRepository medicationOrderRepository,
            QueueTicketRepository queueTicketRepository,
            TriageAssessmentRepository triageAssessmentRepository,
            PatientService patientService,
            QueueService queueService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext,
            ConnectivityMonitor connectivityMonitor,
            NoteComparisonService noteComparisonService,
            @Value("${dalili.clinic.name:Dalili Health Clinic}") String clinicName
    ) {
        this.encounterRepository = encounterRepository;
        this.medicationOrderRepository = medicationOrderRepository;
        this.queueTicketRepository = queueTicketRepository;
        this.triageAssessmentRepository = triageAssessmentRepository;
        this.patientService = patientService;
        this.queueService = queueService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
        this.connectivityMonitor = connectivityMonitor;
        this.noteComparisonService = noteComparisonService;
        this.clinicName = clinicName;
    }

    // ==================== PRE-ENCOUNTER PREVIEW ====================

    /**
     * Generates a comprehensive pre-encounter preview for a queue ticket.
     *
     * <p>This aggregates all relevant patient information including:
     * <ul>
     *   <li>Patient demographics</li>
     *   <li>Queue and wait time details</li>
     *   <li>Full triage assessment with vitals and red flags</li>
     *   <li>Active medications from history</li>
     *   <li>Clinical alerts and warnings</li>
     *   <li>Recent encounter history</li>
     * </ul>
     * </p>
     *
     * <p>Use this before calling a patient to review their clinical picture.</p>
     *
     * @param queueTicketId the queue ticket UUID
     * @return comprehensive encounter preview
     * @throws EncounterException if ticket or patient not found
     */
    public EncounterPreview getPreEncounterPreview(UUID queueTicketId) {
        auditGuard.assertSessionActive();

        QueueTicket ticket = queueTicketRepository.findById(queueTicketId)
                .orElseThrow(() -> new EncounterException("Queue ticket not found"));

        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(ticket.getPatientId()));
        if (patientOpt.isEmpty()) {
            throw new EncounterException("Patient not found");
        }
        Patient patient = patientOpt.get();

        // Build patient summary
        EncounterPreview.PatientSummary patientSummary = EncounterPreview.PatientSummary.from(patient);

        // Build queue summary
        EncounterPreview.QueueSummary queueSummary = EncounterPreview.QueueSummary.from(ticket);

        // Build triage summary (if exists)
        EncounterPreview.TriageSummary triageSummary = null;
        if (ticket.getTriageAssessmentId() != null) {
            Optional<TriageAssessment> triageOpt = triageAssessmentRepository.findById(ticket.getTriageAssessmentId());
            if (triageOpt.isPresent()) {
                triageSummary = EncounterPreview.TriageSummary.from(triageOpt.get());
            }
        }

        // Build medication history
        List<EncounterPreview.MedicationHistory> activeMedications = buildMedicationHistory(patient.getId());

        // Build clinical alerts
        List<EncounterPreview.ClinicalAlert> alerts = buildClinicalAlerts(patient, ticket, triageSummary);

        // Build historical datasets for repeat-care context
        List<Encounter> completedEncounters = encounterRepository.findCompletedByPatientId(patient.getId());
        List<EncounterPreview.PreviousEncounterSummary> recentEncounters = buildRecentEncounters(completedEncounters);
        EncounterPreview.RepeatCareSummary repeatCareSummary = buildRepeatCareSummary(
                completedEncounters,
                sessionContext.userId(),
                sessionContext.username()
        );
        List<EncounterPreview.HistoricalDiagnosis> diagnosisHistory = buildDiagnosisHistory(
                completedEncounters,
                sessionContext.userId()
        );
        List<EncounterPreview.VitalTrendPoint> vitalTrends = buildVitalsTrend(patient.getId());
        List<EncounterPreview.CarePlanHistory> carePlanHistory = buildCarePlanHistory(completedEncounters);

        // Audit the preview access
        auditService.record("ENCOUNTER_PREVIEW_ACCESSED", patient.getId(),
                String.format("Pre-encounter preview accessed for ticket %s", ticket.getTicketNumber()));

        return new EncounterPreview(
                patientSummary,
                queueSummary,
                triageSummary,
                activeMedications,
                alerts,
                recentEncounters,
                repeatCareSummary,
                diagnosisHistory,
                vitalTrends,
                carePlanHistory
        );
    }

    /**
     * Builds medication history from previous encounters.
     */
    private List<EncounterPreview.MedicationHistory> buildMedicationHistory(UUID patientId) {
        List<EncounterPreview.MedicationHistory> medications = new ArrayList<>();

        // Get recent medication orders for patient
        List<MedicationOrder> recentOrders = medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId);

        // Take last 10 unique medications
        recentOrders.stream()
                .limit(10)
                .forEach(order -> {
                    medications.add(new EncounterPreview.MedicationHistory(
                            order.getMedicationName(),
                            order.getDosage(),
                            order.getFrequency(),
                            order.getOrderedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                            order.getOrderedByName(),
                            true, // Needs reconciliation UI to confirm
                            true  // Needs reconciliation
                    ));
                });

        return medications;
    }

    /**
     * Builds clinical alerts based on patient data, queue status, and triage findings.
     */
    private List<EncounterPreview.ClinicalAlert> buildClinicalAlerts(
            Patient patient,
            QueueTicket ticket,
            EncounterPreview.TriageSummary triage
    ) {
        List<EncounterPreview.ClinicalAlert> alerts = new ArrayList<>();

        // Age-based alerts
        int age = java.time.Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears();
        if (age < 5) {
            alerts.add(new EncounterPreview.ClinicalAlert(
                    EncounterPreview.AlertSeverity.INFO,
                    EncounterPreview.AlertType.PEDIATRIC,
                    "Pediatric patient",
                    String.format("Patient is %d years old. Use age-appropriate dosing.", age)
            ));
        } else if (age >= 65) {
            alerts.add(new EncounterPreview.ClinicalAlert(
                    EncounterPreview.AlertSeverity.INFO,
                    EncounterPreview.AlertType.ELDERLY,
                    "Elderly patient",
                    String.format("Patient is %d years old. Consider renal/hepatic function.", age)
            ));
        }

        // Queue alerts
        if (ticket.isOverdue()) {
            alerts.add(new EncounterPreview.ClinicalAlert(
                    EncounterPreview.AlertSeverity.WARNING,
                    EncounterPreview.AlertType.OVERDUE,
                    "Wait time exceeded",
                    String.format("Patient has waited %d minutes (target: %d minutes)",
                            ticket.getWaitTimeMinutes(), ticket.getTargetWaitMinutes())
            ));
        }

        if (ticket.isAmbulanceArrival()) {
            alerts.add(new EncounterPreview.ClinicalAlert(
                    EncounterPreview.AlertSeverity.WARNING,
                    EncounterPreview.AlertType.RED_FLAG,
                    "Ambulance arrival",
                    "Patient arrived by ambulance"
            ));
        }

        // Triage alerts
        if (triage != null) {
            // Red flag alerts
            if (triage.hasRedFlags()) {
                if (triage.chestPain()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "CHEST PAIN",
                            "Patient presenting with chest pain. Consider cardiac workup."
                    ));
                }
                if (triage.strokeSymptoms()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "STROKE SYMPTOMS",
                            "FAST positive. Time-critical intervention may be needed."
                    ));
                }
                if (triage.difficultyBreathing()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "RESPIRATORY DISTRESS",
                            "Patient has difficulty breathing."
                    ));
                }
                if (triage.severebleeding()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "SEVERE BLEEDING",
                            "Patient has severe or uncontrolled bleeding."
                    ));
                }
                if (triage.alteredMentalStatus()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "ALTERED MENTAL STATUS",
                            "Patient shows confusion or altered consciousness."
                    ));
                }
                if (triage.allergicReaction()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.RED_FLAG,
                            "ALLERGIC REACTION",
                            "Signs of severe allergic reaction. Monitor for anaphylaxis."
                    ));
                }
                if (triage.pregnancyConcern()) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.PREGNANCY,
                            "PREGNANCY CONCERN",
                            "Pregnant patient with concerning symptoms."
                    ));
                }
            }

            // Vital sign abnormalities
            if (triage.oxygenSaturation() != null && triage.oxygenSaturation() < 95) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        triage.oxygenSaturation() < 90 ?
                                EncounterPreview.AlertSeverity.CRITICAL :
                                EncounterPreview.AlertSeverity.WARNING,
                        EncounterPreview.AlertType.VITAL_ABNORMALITY,
                        "Low oxygen saturation",
                        String.format("SpO2: %d%%", triage.oxygenSaturation())
                ));
            }

            if (triage.temperatureCelsius() != null &&
                    triage.temperatureCelsius().compareTo(BigDecimal.valueOf(38.5)) >= 0) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        triage.temperatureCelsius().compareTo(BigDecimal.valueOf(40.0)) >= 0 ?
                                EncounterPreview.AlertSeverity.CRITICAL :
                                EncounterPreview.AlertSeverity.WARNING,
                        EncounterPreview.AlertType.VITAL_ABNORMALITY,
                        "Elevated temperature",
                        String.format("Temperature: %s°C", triage.temperatureCelsius())
                ));
            }

            if (triage.bloodPressureSystolic() != null) {
                if (triage.bloodPressureSystolic() >= 180 ||
                        (triage.bloodPressureDiastolic() != null && triage.bloodPressureDiastolic() >= 120)) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.VITAL_ABNORMALITY,
                            "Hypertensive crisis",
                            String.format("BP: %d/%d mmHg",
                                    triage.bloodPressureSystolic(), triage.bloodPressureDiastolic())
                    ));
                } else if (triage.bloodPressureSystolic() < 90) {
                    alerts.add(new EncounterPreview.ClinicalAlert(
                            EncounterPreview.AlertSeverity.CRITICAL,
                            EncounterPreview.AlertType.VITAL_ABNORMALITY,
                            "Hypotension",
                            String.format("BP: %d/%d mmHg",
                                    triage.bloodPressureSystolic(), triage.bloodPressureDiastolic())
                    ));
                }
            }

            if (triage.painScore() != null && triage.painScore() >= 7) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        triage.painScore() >= 8 ?
                                EncounterPreview.AlertSeverity.WARNING :
                                EncounterPreview.AlertSeverity.INFO,
                        EncounterPreview.AlertType.VITAL_ABNORMALITY,
                        "Significant pain",
                        String.format("Pain score: %d/10", triage.painScore())
                ));
            }

            // Consciousness level
            if (triage.consciousnessLevel() != null &&
                    triage.consciousnessLevel() != TriageAssessment.ConsciousnessLevel.ALERT) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        EncounterPreview.AlertSeverity.CRITICAL,
                        EncounterPreview.AlertType.VITAL_ABNORMALITY,
                        "Altered consciousness",
                        String.format("AVPU: %s", triage.consciousnessLevel())
                ));
            }

            // Allergies
            if (triage.allergies() != null && !triage.allergies().isBlank()) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        EncounterPreview.AlertSeverity.WARNING,
                        EncounterPreview.AlertType.ALLERGY,
                        "Known allergies",
                        triage.allergies()
                ));
            }

            // Triage override
            if (triage.triageOverridden()) {
                alerts.add(new EncounterPreview.ClinicalAlert(
                        EncounterPreview.AlertSeverity.INFO,
                        EncounterPreview.AlertType.VITAL_ABNORMALITY,
                        "Triage overridden",
                        String.format("Nurse override: %s → %s. Reason: %s",
                                triage.systemTriageLevel(), triage.finalTriageLevel(), triage.overrideReason())
                ));
            }
        }

        return alerts;
    }

    /**
     * Builds recent encounter summaries.
     */
    private List<EncounterPreview.PreviousEncounterSummary> buildRecentEncounters(List<Encounter> completedEncounters) {
        List<EncounterPreview.PreviousEncounterSummary> summaries = new ArrayList<>();

        completedEncounters.stream()
                .limit(5)
                .forEach(e -> {
                    String primaryDiagnosis = e.getDiagnoses().stream()
                            .filter(Diagnosis::isPrimary)
                            .findFirst()
                            .map(d -> d.getIcdCode() + " - " + d.getDescription())
                            .orElse("No primary diagnosis");

                    summaries.add(new EncounterPreview.PreviousEncounterSummary(
                            e.getId(),
                            e.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                            e.getEncounterType().name(),
                            primaryDiagnosis,
                            e.getClinicianName()
                    ));
                });

        return summaries;
    }

    private EncounterPreview.RepeatCareSummary buildRepeatCareSummary(
            List<Encounter> completedEncounters,
            UUID currentClinicianId,
            String currentClinicianName
    ) {
        List<Encounter> clinicianEncounters = completedEncounters.stream()
                .filter(encounter -> currentClinicianId != null && currentClinicianId.equals(encounter.getClinicianId()))
                .toList();

        LocalDate lastVisit = clinicianEncounters.stream()
                .map(encounter -> encounter.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(null);

        return new EncounterPreview.RepeatCareSummary(
                !clinicianEncounters.isEmpty(),
                currentClinicianId,
                currentClinicianName,
                clinicianEncounters.size(),
                completedEncounters.size(),
                lastVisit
        );
    }

    private List<EncounterPreview.HistoricalDiagnosis> buildDiagnosisHistory(
            List<Encounter> completedEncounters,
            UUID currentClinicianId
    ) {
        List<EncounterPreview.HistoricalDiagnosis> history = new ArrayList<>();

        completedEncounters.stream()
                .limit(12)
                .forEach(encounter -> {
                    LocalDate encounterDate = encounter.getStartedAt()
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate();

                    encounter.getDiagnoses().stream()
                            .limit(5)
                            .forEach(diagnosis -> history.add(new EncounterPreview.HistoricalDiagnosis(
                                    encounter.getId(),
                                    encounterDate,
                                    diagnosis.getIcdCode(),
                                    diagnosis.getDescription(),
                                    diagnosis.isPrimary(),
                                    encounter.getClinicianName(),
                                    currentClinicianId != null && currentClinicianId.equals(encounter.getClinicianId())
                            )));
                });

        if (history.size() <= 30) {
            return history;
        }
        return history.subList(0, 30);
    }

    private List<EncounterPreview.VitalTrendPoint> buildVitalsTrend(UUID patientId) {
        return triageAssessmentRepository.findByPatientIdOrderByAssessedAtDesc(patientId)
                .stream()
                .filter(assessment -> assessment.getBloodPressureSystolic() != null
                        || assessment.getBloodPressureDiastolic() != null
                        || assessment.getHeartRateBpm() != null
                        || assessment.getOxygenSaturation() != null
                        || assessment.getTemperatureCelsius() != null
                        || assessment.getPainScore() != null)
                .limit(12)
                .sorted(Comparator.comparing(TriageAssessment::getAssessedAt))
                .map(assessment -> new EncounterPreview.VitalTrendPoint(
                        assessment.getId(),
                        assessment.getAssessedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        assessment.getBloodPressureSystolic(),
                        assessment.getBloodPressureDiastolic(),
                        assessment.getHeartRateBpm(),
                        assessment.getOxygenSaturation(),
                        assessment.getTemperatureCelsius(),
                        assessment.getPainScore()
                ))
                .toList();
    }

    private List<EncounterPreview.CarePlanHistory> buildCarePlanHistory(List<Encounter> completedEncounters) {
        return completedEncounters.stream()
                .filter(encounter -> encounter.getCarePlanSuggestionSummary() != null
                        && !encounter.getCarePlanSuggestionSummary().isBlank())
                .limit(8)
                .map(encounter -> new EncounterPreview.CarePlanHistory(
                        encounter.getId(),
                        encounter.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate(),
                        encounter.getClinicianName(),
                        encounter.getCarePlanSuggestionSummary()
                ))
                .toList();
    }

    // ==================== ENCOUNTER CREATION ====================

    /**
     * Creates an encounter from a queue ticket.
     */
    @Transactional
    public Encounter createFromQueue(UUID queueTicketId, Encounter.EncounterType encounterType) {
        auditGuard.assertSessionActive();
        log.info("Creating encounter from queue ticketId={} encounterType={}", queueTicketId, encounterType);

        QueueTicket ticket = queueTicketRepository.findById(queueTicketId)
                .orElseThrow(() -> new EncounterException("Queue ticket not found"));

        if (encounterRepository.findByQueueTicketId(queueTicketId).isPresent()) {
            throw new EncounterException("Encounter already exists for this queue ticket");
        }

        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(ticket.getPatientId()));
        if (patientOpt.isEmpty()) {
            throw new EncounterException("Patient not found");
        }

        UUID triageAssessmentId = ticket.getTriageAssessmentId();

        UUID clinicianId = sessionContext.userId();
        String clinicianName = sessionContext.username();
        String clinicianRole = sessionContext.role() != null ? sessionContext.role().name() : "PHYSICIAN";

        Encounter encounter = Encounter.create(
                ticket.getPatientId(),
                clinicianId,
                clinicianName,
                clinicianRole,
                encounterType,
                ticket.getInitialComplaint()
        );
        encounter.linkToQueue(queueTicketId, triageAssessmentId);

        encounter = encounterRepository.save(encounter);

        auditService.record("ENCOUNTER_CREATED", ticket.getPatientId(),
                String.format("Encounter created from ticket %s", ticket.getTicketNumber()));
        log.info("Encounter created from queue encounterId={} patientId={} queueTicketId={}",
                encounter.getId(), encounter.getPatientId(), queueTicketId);

        return encounter;
    }

    /**
     * Creates a standalone encounter.
     */
    @Transactional
    public Encounter createStandalone(UUID patientId, Encounter.EncounterType encounterType, String chiefComplaint) {
        auditGuard.assertSessionActive();
        log.info("Creating standalone encounter patientId={} encounterType={}", patientId, encounterType);

        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(patientId));
        if (patientOpt.isEmpty()) {
            throw new EncounterException("Patient not found");
        }

        UUID clinicianId = sessionContext.userId();
        String clinicianName = sessionContext.username();
        String clinicianRole = sessionContext.role() != null ? sessionContext.role().name() : "PHYSICIAN";

        Encounter encounter = Encounter.create(
                patientId,
                clinicianId,
                clinicianName,
                clinicianRole,
                encounterType,
                chiefComplaint
        );

        encounter = encounterRepository.save(encounter);

        auditService.record("ENCOUNTER_CREATED", patientId,
                String.format("Standalone encounter created: %s", encounterType));
        log.info("Standalone encounter created encounterId={} patientId={}", encounter.getId(), encounter.getPatientId());

        return encounter;
    }

    // ==================== CLINICAL NOTES ====================

    /**
     * Records transcript.
     */
    @Transactional
    public Encounter recordTranscript(UUID encounterId, String transcript) {
        auditGuard.assertSessionActive();
        log.info("Recording encounter transcript encounterId={}", encounterId);

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.recordTranscript(transcript);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("TRANSCRIPT_RECORDED", encounter.getPatientId(),
                "Ambient AI transcript recorded");
        log.info("Encounter transcript recorded encounterId={} patientId={}", encounter.getId(), encounter.getPatientId());

        return encounter;
    }

    /**
     * Records AI draft.
     */
    @Transactional
    public Encounter recordAiDraft(
            UUID encounterId,
            String draftNote,
            String modelVersion,
            String promptVersion
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.recordAiDraft(draftNote, modelVersion, promptVersion);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("AI_DRAFT_RECORDED", encounter.getPatientId(),
                String.format("AI draft generated using model %s", modelVersion));

        return encounter;
    }

    /**
     * Records physician note.
     */
    @Transactional
    public Encounter recordPhysicianNote(UUID encounterId, String note) {
        auditGuard.assertSessionActive();
        log.info("Recording physician note encounterId={} author={}", encounterId, sessionContext.username());

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.recordPhysicianNote(note);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("PHYSICIAN_NOTE_RECORDED", encounter.getPatientId(),
                "Physician authored note recorded");
        log.info("Physician note recorded encounterId={} patientId={}", encounter.getId(), encounter.getPatientId());

        return encounter;
    }

    /**
     * Confirms note with AI comparison when available, otherwise uses manual/offline confirmation.
     */
    @Transactional
    public Encounter confirmNote(
            UUID encounterId,
            String finalNote,
            String correctionComments
    ) {
        auditGuard.assertSessionActive();
        log.info("Confirming encounter note encounterId={} confirmer={}", encounterId, sessionContext.username());

        Encounter encounter = findByIdOrThrow(encounterId);
        String staffId = sessionContext.username();
        boolean aiAvailable = connectivityMonitor.isAiAvailable();
        boolean canRunAiComparison = aiAvailable && encounter.hasTranscript();
        TranscriptValidationResult validation;

        try {
            if (canRunAiComparison) {
                validation = validateAgainstTranscriptWithAi(encounter, finalNote);
                encounter.confirmNoteWithSystemAssessment(
                        finalNote,
                        validation.rating(),
                        validation.score(),
                        validation.summary(),
                        correctionComments,
                        staffId
                );
            } else {
                String summary = aiAvailable
                        ? "AI comparison skipped because transcript is not available for this encounter."
                        : "AI comparison skipped because system is offline or AI is unavailable.";
                validation = new TranscriptValidationResult(
                        Encounter.NoteAccuracyRating.NOT_ASSESSED,
                        null,
                        summary
                );
                encounter.confirmNoteWithoutAi(finalNote, staffId);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        String ratingInfo = String.format(" AI accuracy(system): %s (%s).",
                validation.rating(),
                validation.score() != null ? validation.score() + "%" : "n/a");
        auditService.record("NOTE_CONFIRMED", encounter.getPatientId(),
                "Final note confirmed." + ratingInfo + " " + validation.summary());
        log.info("Encounter note confirmed encounterId={} patientId={} aiComparisonPerformed={} score={}",
                encounter.getId(), encounter.getPatientId(), canRunAiComparison, validation.score());

        return encounter;
    }

    // ==================== DIAGNOSES ====================

    @Transactional
    public Encounter addDiagnosis(
            UUID encounterId,
            String icdCode,
            String description,
            boolean isPrimary,
            Diagnosis.DiagnosisType type
    ) {
        auditGuard.assertSessionActive();
        log.info("Adding encounter diagnosis encounterId={} diagnosisType={} isPrimary={}",
                encounterId, type, isPrimary);

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            String staffId = sessionContext.username();
            Diagnosis diagnosis = Diagnosis.create(icdCode, description, isPrimary, type, staffId);
            encounter.addDiagnosis(diagnosis);
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("DIAGNOSIS_ADDED", encounter.getPatientId(),
                String.format("Diagnosis added: %s - %s", icdCode, description));
        log.info("Encounter diagnosis added encounterId={} patientId={} diagnosisCount={}",
                encounter.getId(), encounter.getPatientId(), encounter.getDiagnoses().size());

        return encounter;
    }

    @Transactional
    public Encounter removeDiagnosis(UUID encounterId, UUID diagnosisId) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        Diagnosis toRemove = encounter.getDiagnoses().stream()
                .filter(d -> d.getId().equals(diagnosisId))
                .findFirst()
                .orElseThrow(() -> new EncounterException("Diagnosis not found"));

        try {
            encounter.removeDiagnosis(toRemove);
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("DIAGNOSIS_REMOVED", encounter.getPatientId(),
                String.format("Diagnosis removed: %s", toRemove.getIcdCode()));

        return encounter;
    }

    @Transactional
    public Encounter agreeDiagnoses(UUID encounterId) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);
        String staffId = sessionContext.username();

        try {
            encounter.agreeDiagnoses(staffId);
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("DIAGNOSIS_AGREED", encounter.getPatientId(),
                "Physician agreed diagnosis set");

        return encounter;
    }

    // ==================== MEDICATION ORDERS ====================

    @Transactional
    public Encounter addMedicationOrder(UUID encounterId, MedicationOrderInput input) {
        auditGuard.assertSessionActive();
        log.info("Adding medication order encounterId={} orderedBy={}", encounterId, sessionContext.username());

        Encounter encounter = findByIdOrThrow(encounterId);
        String staffId = sessionContext.username();
        String staffName = sessionContext.username();

        MedicationOrder order = MedicationOrder.create(
                encounter.getPatientId(),
                input.medicationName(),
                input.dosage(),
                input.dosageForm(),
                input.frequency(),
                input.route(),
                input.durationDays(),
                input.quantity(),
                staffId,
                staffName
        );

        if (input.brandName() != null) order.setBrandName(input.brandName());
        if (input.instructions() != null) order.setInstructions(input.instructions());
        if (input.indication() != null) order.setIndication(input.indication());

        try {
            encounter.addMedicationOrder(order);
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("MEDICATION_ORDERED", encounter.getPatientId(),
                String.format("Medication ordered: %s %s", input.medicationName(), input.dosage()));
        log.info("Medication order added encounterId={} patientId={} medicationOrderCount={}",
                encounter.getId(), encounter.getPatientId(), encounter.getMedicationOrders().size());

        return encounter;
    }

    @Transactional
    public Encounter removeMedicationOrder(UUID encounterId, UUID medicationOrderId) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        MedicationOrder toRemove = encounter.getMedicationOrders().stream()
                .filter(m -> m.getId().equals(medicationOrderId))
                .findFirst()
                .orElseThrow(() -> new EncounterException("Medication order not found"));

        try {
            encounter.removeMedicationOrder(toRemove);
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("MEDICATION_REMOVED", encounter.getPatientId(),
                String.format("Medication removed: %s", toRemove.getMedicationName()));

        return encounter;
    }

    // ==================== PRESCRIPTION ====================

    public PrescriptionContent generatePrescription(UUID encounterId) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        if (!encounter.hasMedicationOrders()) {
            throw new EncounterException("No medication orders to generate prescription");
        }

        Optional<Patient> patientOpt = Optional.ofNullable(patientService.findById(encounter.getPatientId()));
        if (patientOpt.isEmpty()) {
            throw new EncounterException("Patient not found");
        }
        Patient patient = patientOpt.get();

        List<MedicationOrder> orders = encounter.getMedicationOrders();

        StringBuilder prescriptionText = new StringBuilder();
        prescriptionText.append("=".repeat(50)).append("\n");
        prescriptionText.append(clinicName.toUpperCase()).append(" - PRESCRIPTION\n");
        prescriptionText.append("=".repeat(50)).append("\n\n");

        prescriptionText.append("Patient: ").append(patient.getFullName()).append("\n");
        prescriptionText.append("MRN: ").append(patient.getMrn()).append("\n");
        prescriptionText.append("DOB: ").append(patient.getDateOfBirth()).append("\n");
        prescriptionText.append("Date: ").append(LocalDate.now()).append("\n");
        prescriptionText.append("Doctor: ").append(encounter.getClinicianName()).append("\n");
        prescriptionText.append("Role: ").append(encounter.getClinicianRole()).append("\n\n");

        prescriptionText.append("-".repeat(50)).append("\n");
        prescriptionText.append("MEDICATIONS:\n");
        prescriptionText.append("-".repeat(50)).append("\n\n");

        int index = 1;
        for (MedicationOrder order : orders) {
            prescriptionText.append(index++).append(". ");
            prescriptionText.append(order.getPrescriptionText());
            prescriptionText.append("\n\n");
        }

        prescriptionText.append("-".repeat(50)).append("\n");
        prescriptionText.append("Prescriber: ").append(encounter.getClinicianName()).append("\n");
        prescriptionText.append("Ordered At: ").append(java.time.LocalDateTime.now(ZoneId.systemDefault())).append("\n");
        prescriptionText.append("Signature: _______________________\n");
        prescriptionText.append("=".repeat(50)).append("\n");

        return new PrescriptionContent(
                encounter.getId(),
                patient.getFullName(),
                patient.getMrn(),
                clinicName,
                encounter.getClinicianName(),
                LocalDate.now(),
                prescriptionText.toString(),
                orders.size()
        );
    }

    @Transactional
    public Encounter markPrescriptionPrinted(UUID encounterId) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            for (MedicationOrder order : encounter.getMedicationOrders()) {
                order.markPrinted();
            }
            encounter.markPrescriptionPrinted();
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("PRESCRIPTION_PRINTED", encounter.getPatientId(),
                String.format("Prescription printed with %d medications",
                        encounter.getMedicationOrders().size()));

        return encounter;
    }

    // ==================== ENCOUNTER COMPLETION ====================

    public CompletionReadiness getCompletionReadiness(UUID encounterId) {
        Encounter encounter = findByIdOrThrow(encounterId);
        return new CompletionReadiness(
                encounter.canComplete(),
                encounter.isNoteConfirmed(),
                encounter.hasDiagnosis(),
                encounter.hasMedicationOrders(),
                encounter.getCompletionReadiness()
        );
    }

    @Transactional
    public Encounter completeEncounter(UUID encounterId) {
        auditGuard.assertSessionActive();
        log.info("Completing encounter encounterId={} completedBy={}", encounterId, sessionContext.username());

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            if (encounter.getQueueTicketId() != null) {
                queueService.completeConsultation(encounter.getQueueTicketId());
            }
            encounter.complete();
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("ENCOUNTER_COMPLETED", encounter.getPatientId(),
                "Encounter completed");
        log.info("Encounter completed encounterId={} patientId={}", encounter.getId(), encounter.getPatientId());

        return encounter;
    }

    @Transactional
    public Encounter cancelEncounter(UUID encounterId, String reason) {
        auditGuard.assertSessionActive();
        log.info("Cancelling encounter encounterId={} cancelledBy={}", encounterId, sessionContext.username());

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.cancel();
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("ENCOUNTER_CANCELLED", encounter.getPatientId(),
                String.format("Encounter cancelled: %s", reason));
        log.info("Encounter cancelled encounterId={} patientId={}", encounter.getId(), encounter.getPatientId());

        return encounter;
    }

    // ==================== RETRIEVAL ====================

    public Optional<Encounter> findById(UUID encounterId) {
        return encounterRepository.findById(encounterId);
    }

    public List<Encounter> getPatientEncounters(UUID patientId) {
        return encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId);
    }

    public List<Encounter> getMyOpenEncounters() {
        UUID clinicianId = sessionContext.userId();
        return encounterRepository.findByClinicianIdAndStatus(clinicianId, Encounter.EncounterStatus.OPEN);
    }

    public Optional<TriageAssessment> getTriageData(UUID encounterId) {
        Encounter encounter = findByIdOrThrow(encounterId);
        if (encounter.getTriageAssessmentId() == null) {
            return Optional.empty();
        }
        return triageAssessmentRepository.findById(encounter.getTriageAssessmentId());
    }

    private Encounter findByIdOrThrow(UUID encounterId) {
        return encounterRepository.findById(encounterId)
                .orElseThrow(() -> new EncounterException("Encounter not found"));
    }

    private TranscriptValidationResult validateAgainstTranscriptWithAi(Encounter encounter, String finalNote) {
        NoteComparisonService.ComparisonResult aiResult = noteComparisonService.compare(
                encounter.getTranscript(),
                encounter.getAiDraftNote(),
                finalNote
        );

        if (aiResult.available()) {
            return new TranscriptValidationResult(
                    mapComparisonRating(aiResult.rating()),
                    aiResult.score(),
                    aiResult.summary()
            );
        }

        TranscriptValidationResult heuristicFallback = validateAgainstTranscript(encounter, finalNote);
        String fallbackSummary = heuristicFallback.summary() + " AI comparison unavailable; local fallback used.";
        if (aiResult.errorMessage() != null && !aiResult.errorMessage().isBlank()) {
            fallbackSummary = fallbackSummary + " Reason: " + aiResult.errorMessage();
        }
        return new TranscriptValidationResult(
                heuristicFallback.rating(),
                heuristicFallback.score(),
                fallbackSummary
        );
    }

    private TranscriptValidationResult validateAgainstTranscript(Encounter encounter, String finalNote) {
        if (encounter.getTranscript() == null || encounter.getTranscript().isBlank()) {
            return new TranscriptValidationResult(
                    Encounter.NoteAccuracyRating.NOT_ASSESSED,
                    null,
                    "No transcript available for discrepancy analysis."
            );
        }

        Set<String> transcriptTokens = tokenizeClinicalTerms(encounter.getTranscript());
        Set<String> noteTokens = tokenizeClinicalTerms(finalNote);

        if (transcriptTokens.isEmpty()) {
            return new TranscriptValidationResult(
                    Encounter.NoteAccuracyRating.NOT_ASSESSED,
                    null,
                    "Transcript did not contain enough clinical terms for scoring."
            );
        }

        Set<String> intersection = new HashSet<>(transcriptTokens);
        intersection.retainAll(noteTokens);

        int score = (int) Math.round((intersection.size() * 100.0) / transcriptTokens.size());
        Encounter.NoteAccuracyRating rating = mapScoreToRating(score);

        Set<String> missing = new HashSet<>(transcriptTokens);
        missing.removeAll(noteTokens);
        List<String> topMissing = missing.stream().limit(12).toList();

        String summary = topMissing.isEmpty()
                ? "No major transcript term discrepancies detected."
                : "Potentially missing transcript terms in final note: " + String.join(", ", topMissing);

        return new TranscriptValidationResult(rating, score, summary);
    }

    private Encounter.NoteAccuracyRating mapComparisonRating(NoteComparisonService.ComparisonRating rating) {
        return switch (rating) {
            case ACCURATE -> Encounter.NoteAccuracyRating.ACCURATE;
            case MINOR_EDITS -> Encounter.NoteAccuracyRating.MINOR_EDITS;
            case MAJOR_EDITS -> Encounter.NoteAccuracyRating.MAJOR_EDITS;
            case UNSAFE -> Encounter.NoteAccuracyRating.UNSAFE;
            case NOT_ASSESSED -> Encounter.NoteAccuracyRating.NOT_ASSESSED;
        };
    }

    private Set<String> tokenizeClinicalTerms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        Set<String> stopWords = Set.of(
                "the", "and", "for", "with", "that", "this", "have", "from", "were", "been",
                "patient", "reports", "report", "today", "history", "present", "past", "normal",
                "note", "notes", "assessment", "plan", "review", "follow", "clinic", "hospital"
        );

        Set<String> tokens = new HashSet<>();
        Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !stopWords.contains(token))
                .forEach(tokens::add);
        return tokens;
    }

    private Encounter.NoteAccuracyRating mapScoreToRating(int score) {
        if (score >= 80) {
            return Encounter.NoteAccuracyRating.ACCURATE;
        }
        if (score >= 60) {
            return Encounter.NoteAccuracyRating.MINOR_EDITS;
        }
        if (score >= 35) {
            return Encounter.NoteAccuracyRating.MAJOR_EDITS;
        }
        return Encounter.NoteAccuracyRating.UNSAFE;
    }

    // ==================== RECORDS ====================

    public record MedicationOrderInput(
            String medicationName,
            String brandName,
            String dosage,
            MedicationOrder.DosageForm dosageForm,
            String frequency,
            MedicationOrder.RouteOfAdministration route,
            int durationDays,
            int quantity,
            String instructions,
            String indication
    ) {
    }

    public record PrescriptionContent(
            UUID encounterId,
            String patientName,
            String mrn,
            String clinicName,
            String orderedBy,
            LocalDate orderDate,
            String prescriptionText,
            int medicationCount
    ) {
    }

    public record TranscriptValidationResult(
            Encounter.NoteAccuracyRating rating,
            Integer score,
            String summary
    ) {
    }

    public record CompletionReadiness(
            boolean canComplete,
            boolean noteConfirmed,
            boolean hasDiagnosis,
            boolean hasMedicationOrders,
            String message
    ) {
    }

    public static class EncounterException extends RuntimeException {
        public EncounterException(String message) {
            super(message);
        }
    }
}


