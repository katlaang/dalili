package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.encounter.model.Diagnosis;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.triage.TriageAssessmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Clinical decision support for advisory care plan suggestions.
 *
 * <p>All output is advisory-only and requires explicit physician agreement
 * before encounter completion when suggestions were generated.</p>
 */
@Service
public class ClinicalDecisionSupportService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalDecisionSupportService.class);

    private static final String ADVISORY_WARNING =
            "Clinical decision support suggestions only. Physician must confirm diagnosis and click Agree before completion.";

    private final EncounterRepository encounterRepository;
    private final TriageAssessmentRepository triageRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public ClinicalDecisionSupportService(
            EncounterRepository encounterRepository,
            TriageAssessmentRepository triageRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.encounterRepository = encounterRepository;
        this.triageRepository = triageRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public CarePlanSuggestionResult suggestCarePlan(UUID encounterId) {
        auditGuard.assertSessionActive();
        log.info("Generating advisory care plan suggestions encounterId={}", encounterId);

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new CarePlanException("Encounter not found"));

        Optional<TriageAssessment> triage = resolveTriage(encounter);
        List<Diagnosis> diagnoses = encounter.getDiagnoses();
        List<String> diagnosisBasis = diagnoses.stream()
                .map(d -> d.getIcdCode() + " - " + d.getDescription())
                .toList();

        List<SuggestedMedication> medications = buildMedicationSuggestions(diagnoses);
        List<String> labs = buildLabSuggestions(diagnoses);
        List<String> treatmentPlan = buildTreatmentPlanSuggestions(diagnoses);

        List<String> currentMeds = extractCurrentMedications(encounter, triage.orElse(null));
        String allergies = triage.map(TriageAssessment::getAllergies).orElse(null);
        List<String> interactionAlerts = enrichInteractions(medications, currentMeds, allergies);

        String summary = buildSummary(diagnosisBasis, labs, medications, interactionAlerts);
        encounter.markCarePlanSuggested(summary);
        encounterRepository.save(encounter);

        auditService.record(
                "CARE_PLAN_SUGGESTED",
                encounter.getPatientId(),
                String.format("Care plan suggestions generated (%d labs, %d meds, %d interaction alerts)",
                        labs.size(), medications.size(), interactionAlerts.size())
        );
        log.info("Care plan suggestions generated encounterId={} patientId={} labs={} meds={} interactions={}",
                encounter.getId(), encounter.getPatientId(), labs.size(), medications.size(), interactionAlerts.size());

        return new CarePlanSuggestionResult(
                encounter.getId(),
                true,
                ADVISORY_WARNING,
                encounter.isCarePlanAgreementRequired(),
                encounter.isCarePlanAgreed(),
                encounter.getCarePlanSuggestedAt(),
                encounter.getCarePlanAgreedAt(),
                encounter.getCarePlanAgreedBy(),
                diagnosisBasis,
                labs,
                treatmentPlan,
                medications,
                interactionAlerts
        );
    }

    @Transactional
    public CarePlanAgreementResult agreeCarePlan(UUID encounterId) {
        auditGuard.assertSessionActive();
        log.info("Recording care plan agreement encounterId={} reviewer={}", encounterId, sessionContext.username());

        Encounter encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new CarePlanException("Encounter not found"));

        String staffId = sessionContext.username();
        encounter.agreeSuggestedCarePlan(staffId);
        encounterRepository.save(encounter);

        auditService.record(
                "CARE_PLAN_AGREED",
                encounter.getPatientId(),
                "Physician agreed with advisory care plan suggestions"
        );
        log.info("Care plan agreement recorded encounterId={} patientId={} agreedBy={}",
                encounter.getId(), encounter.getPatientId(), encounter.getCarePlanAgreedBy());

        return new CarePlanAgreementResult(
                encounter.getId(),
                encounter.isCarePlanAgreed(),
                encounter.getCarePlanAgreedAt(),
                encounter.getCarePlanAgreedBy()
        );
    }

    private Optional<TriageAssessment> resolveTriage(Encounter encounter) {
        if (encounter.getTriageAssessmentId() == null) {
            return Optional.empty();
        }
        return triageRepository.findById(encounter.getTriageAssessmentId());
    }

    private List<String> buildLabSuggestions(List<Diagnosis> diagnoses) {
        Set<String> labs = new LinkedHashSet<>();
        for (Diagnosis diagnosis : diagnoses) {
            String icd = diagnosis.getIcdCode() != null ? diagnosis.getIcdCode().toUpperCase(Locale.ROOT) : "";
            if (icd.startsWith("R07") || icd.startsWith("I2")) {
                labs.add("ECG");
                labs.add("Troponin");
                labs.add("Chest X-ray");
            } else if (icd.startsWith("J18")) {
                labs.add("CBC");
                labs.add("Chest X-ray");
                labs.add("Sputum Gram stain/culture");
            } else if (icd.startsWith("N39")) {
                labs.add("Urinalysis");
                labs.add("Urine culture and sensitivity");
            } else if (icd.startsWith("E11")) {
                labs.add("HbA1c");
                labs.add("Fasting blood glucose");
                labs.add("Renal function tests");
            } else if (icd.startsWith("I10")) {
                labs.add("Renal function tests");
                labs.add("Urinalysis");
                labs.add("ECG");
            } else if (icd.startsWith("A09")) {
                labs.add("Serum electrolytes");
                labs.add("Stool analysis");
            } else if (icd.startsWith("J06")) {
                labs.add("No routine labs unless red flags present");
            }
        }
        return List.copyOf(labs);
    }

    private List<String> buildTreatmentPlanSuggestions(List<Diagnosis> diagnoses) {
        Set<String> plans = new LinkedHashSet<>();
        for (Diagnosis diagnosis : diagnoses) {
            String icd = diagnosis.getIcdCode() != null ? diagnosis.getIcdCode().toUpperCase(Locale.ROOT) : "";
            if (icd.startsWith("R07") || icd.startsWith("I2")) {
                plans.add("Treat as possible ACS until ruled out; monitor vitals continuously.");
                plans.add("Escalate immediately for any hemodynamic instability.");
            } else if (icd.startsWith("J18")) {
                plans.add("Assess severity and consider oxygen support if SpO2 < 92%.");
                plans.add("Review antibiotic response within 48-72 hours.");
            } else if (icd.startsWith("N39")) {
                plans.add("Encourage oral hydration and review urine culture results for antibiotic adjustment.");
            } else if (icd.startsWith("E11")) {
                plans.add("Provide glycemic control counseling and schedule follow-up glucose review.");
            } else if (icd.startsWith("I10")) {
                plans.add("Lifestyle counseling and blood pressure follow-up plan.");
            } else if (icd.startsWith("A09")) {
                plans.add("Oral rehydration and dehydration monitoring.");
            } else if (icd.startsWith("J06")) {
                plans.add("Supportive care and safety-net advice for worsening symptoms.");
            }
        }
        if (plans.isEmpty()) {
            plans.add("No diagnosis-specific template available; apply clinician judgment.");
        }
        return List.copyOf(plans);
    }

    private List<SuggestedMedication> buildMedicationSuggestions(List<Diagnosis> diagnoses) {
        List<SuggestedMedication> meds = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();

        for (Diagnosis diagnosis : diagnoses) {
            String icd = diagnosis.getIcdCode() != null ? diagnosis.getIcdCode().toUpperCase(Locale.ROOT) : "";
            if (icd.startsWith("J06")) {
                addMedication(unique, meds, "Paracetamol", "500mg", "Q8H PRN", "ORAL", "Symptom relief");
                addMedication(unique, meds, "Cetirizine", "10mg", "OD", "ORAL", "Upper airway symptom control");
            } else if (icd.startsWith("J18")) {
                addMedication(unique, meds, "Amoxicillin/Clavulanate", "625mg", "TDS", "ORAL", "Empiric CAP coverage");
                addMedication(unique, meds, "Paracetamol", "500mg", "Q8H PRN", "ORAL", "Fever control");
            } else if (icd.startsWith("N39")) {
                addMedication(unique, meds, "Nitrofurantoin", "100mg", "BD", "ORAL", "Empiric uncomplicated UTI treatment");
            } else if (icd.startsWith("I10")) {
                addMedication(unique, meds, "Amlodipine", "5mg", "OD", "ORAL", "Initial blood pressure control");
            } else if (icd.startsWith("E11")) {
                addMedication(unique, meds, "Metformin", "500mg", "BD", "ORAL", "First-line glycemic control");
            } else if (icd.startsWith("A09")) {
                addMedication(unique, meds, "Oral Rehydration Salts", "1 sachet", "After each stool", "ORAL", "Rehydration");
                addMedication(unique, meds, "Zinc", "20mg", "OD", "ORAL", "Adjunct in diarrheal illness");
            } else if (icd.startsWith("R07") || icd.startsWith("I2")) {
                addMedication(unique, meds, "Aspirin", "300mg stat", "Once", "ORAL", "ACS rule-out protocol");
            }
        }

        return meds;
    }

    private void addMedication(
            Set<String> unique,
            List<SuggestedMedication> meds,
            String name,
            String dose,
            String frequency,
            String route,
            String reason
    ) {
        String key = name.toLowerCase(Locale.ROOT);
        if (unique.add(key)) {
            meds.add(new SuggestedMedication(name, dose, frequency, route, reason, List.of(), List.of()));
        }
    }

    private List<String> extractCurrentMedications(Encounter encounter, TriageAssessment triage) {
        Set<String> meds = new LinkedHashSet<>();
        for (MedicationOrder order : encounter.getMedicationOrders()) {
            if (order.getMedicationName() != null && !order.getMedicationName().isBlank()) {
                meds.add(order.getMedicationName().trim());
            }
        }

        if (triage != null && triage.getCurrentMedications() != null) {
            String[] parts = triage.getCurrentMedications().split("[,;|\\n]");
            for (String part : parts) {
                String value = part.trim();
                if (!value.isBlank()) {
                    meds.add(value);
                }
            }
        }
        return List.copyOf(meds);
    }

    private List<String> enrichInteractions(
            List<SuggestedMedication> suggestions,
            List<String> currentMeds,
            String allergies
    ) {
        Map<String, List<String>> interactionRules = buildInteractionRules();
        List<String> allAlerts = new ArrayList<>();

        for (int i = 0; i < suggestions.size(); i++) {
            SuggestedMedication medication = suggestions.get(i);
            String medKey = medication.name().toLowerCase(Locale.ROOT);
            List<String> interactionWarnings = new ArrayList<>();
            List<String> allergyWarnings = new ArrayList<>();

            List<String> targets = interactionRules.getOrDefault(medKey, List.of());
            for (String current : currentMeds) {
                String currentLower = current.toLowerCase(Locale.ROOT);
                for (String target : targets) {
                    if (currentLower.contains(target)) {
                        String warning = String.format("%s may interact with current medication: %s",
                                medication.name(), current);
                        interactionWarnings.add(warning);
                        allAlerts.add(warning);
                    }
                }
            }

            if (allergies != null && !allergies.isBlank()) {
                String allergyLower = allergies.toLowerCase(Locale.ROOT);
                if (allergyLower.contains(medKey) || allergyLower.contains("penicillin") && medKey.contains("amoxicillin")) {
                    String warning = String.format("Allergy warning: review %s against documented allergies (%s)",
                            medication.name(), allergies);
                    allergyWarnings.add(warning);
                    allAlerts.add(warning);
                }
            }

            suggestions.set(i, new SuggestedMedication(
                    medication.name(),
                    medication.dose(),
                    medication.frequency(),
                    medication.route(),
                    medication.reason(),
                    interactionWarnings,
                    allergyWarnings
            ));
        }

        return allAlerts.stream().distinct().toList();
    }

    private Map<String, List<String>> buildInteractionRules() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("aspirin", List.of("warfarin", "clopidogrel", "heparin"));
        rules.put("ibuprofen", List.of("warfarin", "lisinopril", "losartan"));
        rules.put("diclofenac", List.of("warfarin", "lisinopril", "losartan"));
        rules.put("amoxicillin/clavulanate", List.of("allopurinol", "warfarin"));
        rules.put("azithromycin", List.of("amiodarone", "warfarin", "digoxin"));
        rules.put("ciprofloxacin", List.of("warfarin", "theophylline", "tizanidine"));
        rules.put("metronidazole", List.of("warfarin", "alcohol"));
        rules.put("metformin", List.of("insulin", "glibenclamide", "gliclazide"));
        rules.put("amlodipine", List.of("simvastatin"));
        rules.put("enalapril", List.of("spironolactone", "potassium"));
        rules.put("losartan", List.of("spironolactone", "potassium"));
        rules.put("prednisolone", List.of("ibuprofen", "diclofenac"));
        return rules;
    }

    private String buildSummary(
            List<String> diagnoses,
            List<String> labs,
            List<SuggestedMedication> meds,
            List<String> interactionAlerts
    ) {
        return String.format(
                "Diagnosis basis: %s | Suggested labs: %d | Suggested meds: %d | Interaction alerts: %d",
                diagnoses.isEmpty() ? "none" : String.join("; ", diagnoses),
                labs.size(),
                meds.size(),
                interactionAlerts.size()
        );
    }

    public record SuggestedMedication(
            String name,
            String dose,
            String frequency,
            String route,
            String reason,
            List<String> interactionWarnings,
            List<String> allergyWarnings
    ) {
    }

    public record CarePlanSuggestionResult(
            UUID encounterId,
            boolean advisoryOnly,
            String warning,
            boolean requiresPhysicianAgreement,
            boolean carePlanAgreed,
            Instant suggestedAt,
            Instant agreedAt,
            String agreedBy,
            List<String> diagnosisBasis,
            List<String> suggestedLabs,
            List<String> suggestedTreatmentPlan,
            List<SuggestedMedication> suggestedMedications,
            List<String> interactionAlerts
    ) {
    }

    public record CarePlanAgreementResult(
            UUID encounterId,
            boolean carePlanAgreed,
            Instant agreedAt,
            String agreedBy
    ) {
    }

    public static class CarePlanException extends RuntimeException {
        public CarePlanException(String message) {
            super(message);
        }
    }
}


