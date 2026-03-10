package dalili.com.base.domain.safety;

import dalili.com.base.domain.policy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RedFlagScanner performs deterministic keyword and vital threshold detection.
 * <p>
 * THIS IS NOT LLM-BASED - runs 100% offline.
 * Used at every checkpoint (kiosk, nurse triage, clinician).
 * <p>
 * Deterministic rules ALWAYS win over LLM suggestions for Level 2/3 alerts.
 */
public class RedFlagScanner {

    private static final Logger log = LoggerFactory.getLogger(RedFlagScanner.class);

    private final PolicyPackage policy;

    public RedFlagScanner(PolicyPackage policy) {
        this.policy = policy;
    }

    /**
     * Scan text for red flag phrases.
     */
    public RedFlagScanResult scanText(String text) {
        if (text == null || text.isBlank()) {
            return RedFlagScanResult.empty();
        }

        List<RedFlagMatch> matches = new ArrayList<>();
        int totalPoints = 0;
        RedFlagSeverity highestSeverity = null;

        // Scan critical phrases
        for (RedFlagRule rule : policy.redFlagPhrases().critical()) {
            if (rule.matches(text)) {
                int points = rule.getEscalationPoints();
                totalPoints += points;
                matches.add(new RedFlagMatch(rule.phrase(), rule.category(),
                        rule.suggests(), RedFlagSeverity.CRITICAL, points));
                highestSeverity = RedFlagSeverity.CRITICAL;
            }
        }

        // Scan high phrases
        for (RedFlagRule rule : policy.redFlagPhrases().high()) {
            if (rule.matches(text)) {
                int points = rule.getEscalationPoints();
                totalPoints += points;
                matches.add(new RedFlagMatch(rule.phrase(), rule.category(),
                        rule.suggests(), RedFlagSeverity.HIGH, points));
                if (highestSeverity == null) highestSeverity = RedFlagSeverity.HIGH;
            }
        }

        // Scan moderate phrases
        for (RedFlagRule rule : policy.redFlagPhrases().moderate()) {
            if (rule.matches(text)) {
                int points = rule.getEscalationPoints();
                totalPoints += points;
                matches.add(new RedFlagMatch(rule.phrase(), rule.category(),
                        rule.suggests(), RedFlagSeverity.MODERATE, points));
                if (highestSeverity == null) highestSeverity = RedFlagSeverity.MODERATE;
            }
        }

        if (!matches.isEmpty()) {
            log.info("Red flags detected: {} matches, {} points, highest: {}",
                    matches.size(), totalPoints, highestSeverity);
        }

        return new RedFlagScanResult(matches, totalPoints, highestSeverity);
    }

    /**
     * Scan vitals against thresholds.
     */
    public VitalScanResult scanVitals(VitalSigns vitals, int ageYears) {
        if (vitals == null) {
            return VitalScanResult.empty();
        }

        AgeGroupThresholds thresholds = policy.getThresholdsForAge(ageYears);
        if (thresholds == null) {
            return VitalScanResult.empty();
        }

        ThresholdEvaluation evaluation = thresholds.evaluate(vitals);

        int points = 0;
        for (ThresholdViolation v : evaluation.violations()) {
            points += policy.escalationScoring().points().criticalVital();
        }

        return new VitalScanResult(
                evaluation.acuityLevel(),
                evaluation.violations(),
                points
        );
    }

    /**
     * Perform full safety scan (text + vitals).
     */
    public SafetyScanResult fullScan(SafetyScanInput input) {
        Instant scannedAt = Instant.now();

        // Scan all text sources
        StringBuilder allText = new StringBuilder();
        if (input.chiefComplaint() != null) allText.append(input.chiefComplaint()).append(" ");
        if (input.symptoms() != null) {
            for (String s : input.symptoms()) allText.append(s).append(" ");
        }
        if (input.nurseNotes() != null) allText.append(input.nurseNotes()).append(" ");

        RedFlagScanResult textResult = scanText(allText.toString());
        VitalScanResult vitalResult = scanVitals(input.vitals(), input.ageYears());

        int totalPoints = textResult.totalPoints() + vitalResult.points();
        AlertLevel alertLevel = policy.escalationScoring().determineLevel(totalPoints);

        // Determine protocols to activate
        List<String> protocolIds = new ArrayList<>();
        for (RedFlagMatch match : textResult.matches()) {
            // Map categories to protocols
            String protocol = categoryToProtocol(match.category());
            if (protocol != null && !protocolIds.contains(protocol)) {
                protocolIds.add(protocol);
            }
        }

        return new SafetyScanResult(
                policy.version(),
                scannedAt,
                textResult,
                vitalResult,
                totalPoints,
                alertLevel,
                protocolIds,
                buildRecommendations(textResult, vitalResult, alertLevel)
        );
    }

    private String categoryToProtocol(String category) {
        return switch (category) {
            case "CARDIOVASCULAR" -> "chest_pain";
            case "NEUROLOGICAL" -> "stroke";
            case "RESPIRATORY" -> null; // No specific protocol in Kenya v1
            case "GI_HEMORRHAGE" -> null;
            case "OBSTETRIC" -> "ectopic";
            case "GI_INFECTIOUS" -> "cholera";
            case "PEDIATRIC" -> "pediatric_dehydration";
            default -> null;
        };
    }

    private List<String> buildRecommendations(RedFlagScanResult text, VitalScanResult vitals, AlertLevel level) {
        List<String> recs = new ArrayList<>();

        if (level == AlertLevel.LEVEL_3) {
            recs.add("IMMEDIATE physician notification required");
        }

        if (text.hasCriticalMatch()) {
            recs.add("Critical symptom pattern detected - do not delay assessment");
        }

        if (vitals.acuityLevel() == AcuityLevel.EMERGENCY) {
            recs.add("Vital signs indicate emergency - establish IV access");
        }

        // Add suggested conditions to rule out
        Set<String> ruleOuts = new LinkedHashSet<>();
        for (RedFlagMatch match : text.matches()) {
            ruleOuts.addAll(match.suggests());
        }
        if (!ruleOuts.isEmpty()) {
            recs.add("Consider ruling out: " + String.join(", ", ruleOuts));
        }

        return recs;
    }
}

record RedFlagScanResult(
        List<RedFlagMatch> matches,
        int totalPoints,
        RedFlagSeverity highestSeverity
) {
    static RedFlagScanResult empty() {
        return new RedFlagScanResult(List.of(), 0, null);
    }

    boolean hasMatches() {
        return !matches.isEmpty();
    }

    boolean hasCriticalMatch() {
        return matches.stream().anyMatch(m -> m.severity() == RedFlagSeverity.CRITICAL);
    }
}

record VitalScanResult(
        AcuityLevel acuityLevel,
        List<ThresholdViolation> violations,
        int points
) {
    static VitalScanResult empty() {
        return new VitalScanResult(AcuityLevel.NON_URGENT, List.of(), 0);
    }

    boolean hasViolations() {
        return !violations.isEmpty();
    }
}

record SafetyScanInput(
        String chiefComplaint,
        List<String> symptoms,
        String nurseNotes,
        VitalSigns vitals,
        int ageYears
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String chiefComplaint;
        private List<String> symptoms = new ArrayList<>();
        private String nurseNotes;
        private VitalSigns vitals;
        private int ageYears;

        public Builder chiefComplaint(String val) {
            this.chiefComplaint = val;
            return this;
        }

        public Builder symptoms(List<String> val) {
            this.symptoms = new ArrayList<>(val);
            return this;
        }

        public Builder nurseNotes(String val) {
            this.nurseNotes = val;
            return this;
        }

        public Builder vitals(VitalSigns val) {
            this.vitals = val;
            return this;
        }

        public Builder ageYears(int val) {
            this.ageYears = val;
            return this;
        }

        public SafetyScanInput build() {
            return new SafetyScanInput(chiefComplaint, symptoms, nurseNotes, vitals, ageYears);
        }
    }
}

record SafetyScanResult(
        String policyVersion,
        Instant scannedAt,
        RedFlagScanResult textScan,
        VitalScanResult vitalScan,
        int totalPoints,
        AlertLevel alertLevel,
        List<String> activatedProtocols,
        List<String> recommendations
) {
    boolean isEmergency() {
        return alertLevel == AlertLevel.LEVEL_3 ||
                vitalScan.acuityLevel() == AcuityLevel.EMERGENCY;
    }

    boolean requiresImmediateAction() {
        return alertLevel.requiresAcknowledgment() || textScan.hasCriticalMatch();
    }
}
