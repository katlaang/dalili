package dalili.com.base.application.service;

import dalili.com.base.domain.escalation.EscalationOrchestrator;
import dalili.com.base.domain.escalation.EscalationResponse;
import dalili.com.base.domain.policy.PolicyLoader;
import dalili.com.base.domain.policy.PolicyPackage;
import dalili.com.base.domain.policy.TriageRuleEngine;
import dalili.com.base.domain.policy.VitalSigns;
import dalili.com.base.domain.triage.TriageAssessment;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that bridges TriageAssessment to the policy evaluation engine.
 * <p>
 * Loads policy from YAML and provides evaluate() method for TriageService.
 * Uses existing TriageRuleEngine and EscalationOrchestrator.
 */
@Service
public class PolicyEscalationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyEscalationService.class);

    private static final String CLASSPATH_POLICY = "policies/kenya-triage-policy-2026.02.yaml";
    private static final String LOCAL_OVERRIDE = "data/policies/kenya-triage-policy-current.yaml";

    private TriageRuleEngine ruleEngine;
    private EscalationOrchestrator orchestrator;
    private String activePolicyVersion;

    @PostConstruct
    public void init() {
        loadPolicy();
    }

    private void loadPolicy() {
        PolicyPackage policy = null;
        PolicyLoader loader = new PolicyLoader();

        // Try local override first (facility-specific)
        Path localPath = Path.of(LOCAL_OVERRIDE);
        if (Files.exists(localPath)) {
            try (InputStream is = Files.newInputStream(localPath)) {
                policy = loader.loadFromInputStream(is);
                log.info("Loaded local policy override: {}", policy.version());
            } catch (Exception e) {
                log.warn("Failed to load local policy: {}", e.getMessage());
            }
        }

        // Fall back to classpath
        if (policy == null) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(CLASSPATH_POLICY)) {
                if (is != null) {
                    policy = loader.loadFromInputStream(is);
                    log.info("Loaded classpath policy: {}", policy.version());
                } else {
                    throw new IllegalStateException("Policy not found: " + CLASSPATH_POLICY);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load policy", e);
            }
        }

        this.ruleEngine = new TriageRuleEngine(policy);
        this.orchestrator = new EscalationOrchestrator(ruleEngine);
        this.activePolicyVersion = policy.version();

        log.info("Policy engine ready: {}", activePolicyVersion);
    }

    /**
     * Evaluate a TriageAssessment and return escalation response.
     */
    public EscalationResponse evaluate(TriageAssessment assessment) {
        TriageRuleEngine.TriageInput input = buildTriageInput(assessment);
        return orchestrator.evaluate(input);
    }

    private TriageRuleEngine.TriageInput buildTriageInput(TriageAssessment assessment) {
        TriageRuleEngine.TriageInput.Builder builder = TriageRuleEngine.TriageInput.builder()
                .ageYears(calculateAge(assessment))
                .sex(null) // Sex is not currently captured on TriageAssessment
                .chiefComplaint(assessment.getChiefComplaint())
                .nurseNotes(assessment.getNursingNotes());

        if (hasVitals(assessment)) {
            builder.vitals(buildVitalSigns(assessment));
        }

        List<String> symptoms = extractSymptoms(assessment);
        if (!symptoms.isEmpty()) {
            builder.symptoms(symptoms);
        }

        return builder.build();
    }

    private VitalSigns buildVitalSigns(TriageAssessment assessment) {
        return VitalSigns.builder()
                .systolicBp(assessment.getBloodPressureSystolic())
                .diastolicBp(assessment.getBloodPressureDiastolic())
                .oxygenSaturation(assessment.getOxygenSaturation())
                .heartRate(assessment.getHeartRateBpm())
                .temperature(
                        assessment.getTemperatureCelsius() != null
                                ? assessment.getTemperatureCelsius().doubleValue()
                                : null
                )
                .respiratoryRate(assessment.getRespiratoryRate())
                .gcs(avpuToGcs(assessment.getConsciousnessLevel()))
                .build();
    }

    private boolean hasVitals(TriageAssessment assessment) {
        return assessment.getBloodPressureSystolic() != null ||
                assessment.getHeartRateBpm() != null ||
                assessment.getRespiratoryRate() != null ||
                assessment.getTemperatureCelsius() != null ||
                assessment.getOxygenSaturation() != null;
    }

    private List<String> extractSymptoms(TriageAssessment assessment) {
        List<String> symptoms = new ArrayList<>();
        if (assessment.isChestPain()) symptoms.add("chest pain");
        if (assessment.isDifficultyBreathing()) symptoms.add("difficulty breathing");
        if (assessment.isStrokeSymptoms()) symptoms.add("stroke symptoms");
        if (assessment.isSeverebleeding()) symptoms.add("severe bleeding");
        if (assessment.isAllergicReaction()) symptoms.add("allergic reaction");
        if (assessment.isAlteredMentalStatus()) symptoms.add("altered mental status");
        if (assessment.isPregnancyConcern()) symptoms.add("pregnancy concern");
        if (assessment.isSevereAbdominalPain()) symptoms.add("severe abdominal pain");

        if (assessment.getChiefComplaint() != null && !assessment.getChiefComplaint().isBlank()) {
            symptoms.add(assessment.getChiefComplaint());
        }
        return symptoms;
    }

    private int calculateAge(TriageAssessment assessment) {
        if (assessment.getPatientDateOfBirth() != null) {
            return java.time.Period.between(
                    assessment.getPatientDateOfBirth(),
                    java.time.LocalDate.now()
            ).getYears();
        }
        if (assessment.getPatientAgeYears() > 0) {
            return assessment.getPatientAgeYears();
        }
        log.warn("Patient age unknown, defaulting to adult");
        return 30;
    }

    /**
     * AVPU to GCS mapping:
     * ALERT=15, VERBAL=13, PAIN=9, UNRESPONSIVE=3
     */
    private Integer avpuToGcs(TriageAssessment.ConsciousnessLevel level) {
        if (level == null) return null;
        return switch (level) {
            case ALERT -> 15;
            case VOICE -> 13;
            case PAIN -> 9;
            case UNRESPONSIVE -> 3;
        };
    }

    public void reloadPolicy() {
        log.info("Reloading policy...");
        loadPolicy();
    }

    public boolean isReady() {
        return ruleEngine != null && orchestrator != null;
    }
}
