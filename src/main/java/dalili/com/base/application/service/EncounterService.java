package dalili.com.base.application.service;


import dalili.com.base.ambient.session.SessionContext;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final EncounterRepository encounterRepository;
    private final MedicationOrderRepository medicationOrderRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final TriageAssessmentRepository triageAssessmentRepository;
    private final PatientService patientService;
    private final QueueService queueService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public EncounterService(
            EncounterRepository encounterRepository,
            MedicationOrderRepository medicationOrderRepository,
            QueueTicketRepository queueTicketRepository,
            TriageAssessmentRepository triageAssessmentRepository,
            PatientService patientService,
            QueueService queueService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
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

        // Build recent encounters
        List<EncounterPreview.PreviousEncounterSummary> recentEncounters = buildRecentEncounters(patient.getId());

        // Audit the preview access
        auditService.record("ENCOUNTER_PREVIEW_ACCESSED", patient.getId(),
                String.format("Pre-encounter preview accessed for ticket %s", ticket.getTicketNumber()));

        return new EncounterPreview(
                patientSummary,
                queueSummary,
                triageSummary,
                activeMedications,
                alerts,
                recentEncounters
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
    private List<EncounterPreview.PreviousEncounterSummary> buildRecentEncounters(UUID patientId) {
        List<EncounterPreview.PreviousEncounterSummary> summaries = new ArrayList<>();

        List<Encounter> recent = encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId);

        recent.stream()
                .filter(e -> e.getStatus() == Encounter.EncounterStatus.COMPLETED)
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

    // ==================== ENCOUNTER CREATION ====================

    /**
     * Creates an encounter from a queue ticket.
     */
    @Transactional
    public Encounter createFromQueue(UUID queueTicketId, Encounter.EncounterType encounterType) {
        auditGuard.assertSessionActive();

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

        return encounter;
    }

    /**
     * Creates a standalone encounter.
     */
    @Transactional
    public Encounter createStandalone(UUID patientId, Encounter.EncounterType encounterType, String chiefComplaint) {
        auditGuard.assertSessionActive();

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

        return encounter;
    }

    // ==================== CLINICAL NOTES ====================

    /**
     * Records transcript.
     */
    @Transactional
    public Encounter recordTranscript(UUID encounterId, String transcript) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.recordTranscript(transcript);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("TRANSCRIPT_RECORDED", encounter.getPatientId(),
                "Ambient AI transcript recorded");

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

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.recordPhysicianNote(note);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("PHYSICIAN_NOTE_RECORDED", encounter.getPatientId(),
                "Physician authored note recorded");

        return encounter;
    }

    /**
     * Confirms note with AI comparison.
     */
    @Transactional
    public Encounter confirmNote(
            UUID encounterId,
            String finalNote,
            Encounter.NoteAccuracyRating accuracyRating,
            String correctionComments
    ) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);
        String staffId = sessionContext.username();

        try {
            if (encounter.hasAiDraft()) {
                encounter.confirmNote(finalNote, accuracyRating, correctionComments, staffId);
            } else {
                encounter.confirmNoteWithoutAi(finalNote, staffId);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        String ratingInfo = accuracyRating != null ?
                String.format(" AI accuracy: %s", accuracyRating) : "";
        auditService.record("NOTE_CONFIRMED", encounter.getPatientId(),
                "Final note confirmed." + ratingInfo);

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

    // ==================== MEDICATION ORDERS ====================

    @Transactional
    public Encounter addMedicationOrder(UUID encounterId, MedicationOrderInput input) {
        auditGuard.assertSessionActive();

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
        prescriptionText.append("DALILI HEALTH - PRESCRIPTION\n");
        prescriptionText.append("=".repeat(50)).append("\n\n");

        prescriptionText.append("Patient: ").append(patient.getFullName()).append("\n");
        prescriptionText.append("MRN: ").append(patient.getMrn()).append("\n");
        prescriptionText.append("DOB: ").append(patient.getDateOfBirth()).append("\n");
        prescriptionText.append("Date: ").append(LocalDate.now()).append("\n\n");

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
        prescriptionText.append("Signature: _______________________\n");
        prescriptionText.append("=".repeat(50)).append("\n");

        return new PrescriptionContent(
                encounter.getId(),
                patient.getFullName(),
                patient.getMrn(),
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

        return encounter;
    }

    @Transactional
    public Encounter cancelEncounter(UUID encounterId, String reason) {
        auditGuard.assertSessionActive();

        Encounter encounter = findByIdOrThrow(encounterId);

        try {
            encounter.cancel();
        } catch (IllegalStateException e) {
            throw new EncounterException(e.getMessage());
        }

        encounter = encounterRepository.save(encounter);

        auditService.record("ENCOUNTER_CANCELLED", encounter.getPatientId(),
                String.format("Encounter cancelled: %s", reason));

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
            String prescriptionText,
            int medicationCount
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