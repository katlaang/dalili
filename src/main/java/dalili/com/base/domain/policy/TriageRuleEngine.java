package dalili.com.base.domain.policy;

import java.time.Instant;
import java.util.*;

/**
 * TriageRuleEngine executes deterministic triage evaluation using policy rules.
 * <p>
 * This is the core safety layer - runs 100% offline.
 * All decisions are auditable and logged with policy version.
 * <p>
 * Thread-safe for concurrent evaluations.
 */
public class TriageRuleEngine {

    private final PolicyPackage policy;

    public TriageRuleEngine(PolicyPackage policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Policy package is required");
        }
        if (!policy.isActive()) {
            throw new IllegalArgumentException("Policy is not active: " + policy.version());
        }
        this.policy = policy;
    }

    /**
     * Perform full triage evaluation.
     *
     * @param input Patient data including vitals, symptoms, demographics
     * @return Complete triage result with acuity, alerts, protocols, and suggestions
     */
    public TriageResult evaluate(TriageInput input) {
        Instant evaluatedAt = Instant.now();
        int totalPoints = 0;
        List<String> reasons = new ArrayList<>();
        List<String> protocols = new ArrayList<>();
        List<DiagnosisSuggestion> suggestions = new ArrayList<>();
        List<String> suggestedTests = new ArrayList<>();
        AcuityLevel highestAcuity = AcuityLevel.NON_URGENT;

        // 1. Evaluate vital signs
        ThresholdEvaluation vitalEval = evaluateVitals(input);
        if (vitalEval.acuityLevel().isMoreSevereThan(highestAcuity)) {
            highestAcuity = vitalEval.acuityLevel();
        }
        for (ThresholdViolation violation : vitalEval.violations()) {
            totalPoints += policy.escalationScoring().points().criticalVital();
            reasons.add("Vital: " + violation.describe());
        }

        // 2. Scan for red flag phrases
        RedFlagScanResult redFlagResult = scanRedFlags(input.combinedText());
        totalPoints += redFlagResult.totalPoints();
        for (RedFlagMatch match : redFlagResult.matches()) {
            reasons.add("Red flag: " + match.phrase() + " (" + match.category() + ")");
            suggestions.addAll(match.suggests().stream()
                    .map(s -> new DiagnosisSuggestion(null, s, true, 0.0))
                    .toList());
        }

        // 3. Match symptom clusters
        List<ClusterMatchResult> clusterMatches = evaluateSymptomClusters(input);
        for (ClusterMatchResult match : clusterMatches) {
            if (match.matched()) {
                totalPoints += match.escalationPoints();
                reasons.add("Cluster: " + match.clusterId() +
                        " (" + match.matchedSymptoms().size() + "/" + match.totalClusterSymptoms() + " symptoms)");

                if (match.protocol() != null) {
                    protocols.add(match.protocol());
                }
                suggestions.addAll(match.suggests());
                suggestedTests.addAll(match.suggestedTests());

                // Check if cluster urgency is more severe
                AcuityLevel clusterAcuity = clusterUrgencyToAcuity(match.urgency());
                if (clusterAcuity.isMoreSevereThan(highestAcuity)) {
                    highestAcuity = clusterAcuity;
                }
            }
        }

        // 4. Determine alert level
        AlertLevel alertLevel = policy.escalationScoring().determineLevel(totalPoints);

        // 5. If Level 3, force EMERGENCY acuity
        if (alertLevel == AlertLevel.LEVEL_3 && highestAcuity != AcuityLevel.EMERGENCY) {
            highestAcuity = AcuityLevel.EMERGENCY;
        }

        // 6. Get protocol details
        List<Protocol> activatedProtocols = protocols.stream()
                .distinct()
                .map(policy::getProtocol)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(p -> p.isApplicableForAge(input.ageYears()))
                .toList();

        // 7. Deduplicate and rank suggestions
        List<DiagnosisSuggestion> rankedSuggestions = rankSuggestions(suggestions);

        return new TriageResult(
                policy.version(),
                evaluatedAt,
                highestAcuity,
                alertLevel,
                totalPoints,
                List.copyOf(reasons),
                vitalEval.violations(),
                redFlagResult,
                clusterMatches.stream().filter(ClusterMatchResult::matched).toList(),
                activatedProtocols,
                rankedSuggestions,
                suggestedTests.stream().distinct().toList()
        );
    }

    private ThresholdEvaluation evaluateVitals(TriageInput input) {
        if (input.vitals() == null) {
            return new ThresholdEvaluation(AcuityLevel.NON_URGENT, List.of());
        }

        AgeGroupThresholds thresholds = policy.getThresholdsForAge(input.ageYears());
        if (thresholds == null) {
            return new ThresholdEvaluation(AcuityLevel.NON_URGENT, List.of());
        }

        return thresholds.evaluate(input.vitals());
    }

    private RedFlagScanResult scanRedFlags(String text) {
        if (text == null || text.isBlank()) {
            return new RedFlagScanResult(List.of(), 0, null);
        }

        List<RedFlagMatch> matches = new ArrayList<>();
        int totalPoints = 0;
        RedFlagSeverity highestSeverity = null;

        for (RedFlagRule rule : policy.redFlagPhrases().getAllRules()) {
            if (rule.matches(text)) {
                int points = rule.getEscalationPoints();
                totalPoints += points;

                matches.add(new RedFlagMatch(
                        rule.phrase(),
                        rule.category(),
                        rule.suggests(),
                        rule.severity(),
                        points
                ));

                if (highestSeverity == null ||
                        rule.severity().ordinal() < highestSeverity.ordinal()) {
                    highestSeverity = rule.severity();
                }
            }
        }

        return new RedFlagScanResult(matches, totalPoints, highestSeverity);
    }

    private List<ClusterMatchResult> evaluateSymptomClusters(TriageInput input) {
        if (input.symptoms() == null || input.symptoms().isEmpty()) {
            return List.of();
        }

        Set<String> symptomSet = new HashSet<>(input.symptoms());

        return policy.symptomClusters().stream()
                .map(cluster -> cluster.evaluate(symptomSet, input.ageYears(), input.sex()))
                .toList();
    }

    private AcuityLevel clusterUrgencyToAcuity(ClusterUrgency urgency) {
        return switch (urgency) {
            case EMERGENCY -> AcuityLevel.EMERGENCY;
            case URGENT -> AcuityLevel.URGENT;
            case STANDARD -> AcuityLevel.STANDARD;
            case NON_URGENT -> AcuityLevel.NON_URGENT;
        };
    }

    private List<DiagnosisSuggestion> rankSuggestions(List<DiagnosisSuggestion> suggestions) {
        // Deduplicate by code (or name if no code)
        Map<String, DiagnosisSuggestion> unique = new LinkedHashMap<>();
        for (DiagnosisSuggestion suggestion : suggestions) {
            String key = suggestion.code() != null ? suggestion.code() : suggestion.name();
            if (!unique.containsKey(key) ||
                    suggestion.confidence() > unique.get(key).confidence()) {
                unique.put(key, suggestion);
            }
        }

        // Sort: rule-out conditions first, then by confidence descending
        return unique.values().stream()
                .sorted((a, b) -> {
                    if (a.ruleOut() && !b.ruleOut()) return -1;
                    if (!a.ruleOut() && b.ruleOut()) return 1;
                    return Double.compare(b.confidence(), a.confidence());
                })
                .toList();
    }

    public PolicyPackage getPolicy() {
        return policy;
    }

    /**
     * Input data for triage evaluation.
     */
    public record TriageInput(
            VitalSigns vitals,
            List<String> symptoms,
            String chiefComplaint,
            String nurseNotes,
            int ageYears,
            String sex  // "M" or "F"
    ) {

        public static Builder builder() {
            return new Builder();
        }

        /**
         * Combine all text fields for red flag scanning.
         */
        public String combinedText() {
            StringBuilder sb = new StringBuilder();
            if (chiefComplaint != null) sb.append(chiefComplaint).append(" ");
            if (nurseNotes != null) sb.append(nurseNotes).append(" ");
            if (symptoms != null) {
                for (String symptom : symptoms) {
                    sb.append(symptom).append(" ");
                }
            }
            return sb.toString().trim();
        }

        public static class Builder {
            private VitalSigns vitals;
            private List<String> symptoms = new ArrayList<>();
            private String chiefComplaint;
            private String nurseNotes;
            private int ageYears;
            private String sex;

            public Builder vitals(VitalSigns val) {
                this.vitals = val;
                return this;
            }

            public Builder symptoms(List<String> val) {
                this.symptoms = new ArrayList<>(val);
                return this;
            }

            public Builder addSymptom(String val) {
                this.symptoms.add(val);
                return this;
            }

            public Builder chiefComplaint(String val) {
                this.chiefComplaint = val;
                return this;
            }

            public Builder nurseNotes(String val) {
                this.nurseNotes = val;
                return this;
            }

            public Builder ageYears(int val) {
                this.ageYears = val;
                return this;
            }

            public Builder sex(String val) {
                this.sex = val;
                return this;
            }

            public TriageInput build() {
                return new TriageInput(vitals, symptoms, chiefComplaint, nurseNotes, ageYears, sex);
            }
        }
    }

    /**
     * Complete result of triage evaluation.
     */
    public record TriageResult(
            String policyVersion,
            Instant evaluatedAt,
            AcuityLevel acuityLevel,
            AlertLevel alertLevel,
            int totalPoints,
            List<String> reasons,
            List<ThresholdViolation> vitalViolations,
            RedFlagScanResult redFlagResult,
            List<ClusterMatchResult> matchedClusters,
            List<Protocol> activatedProtocols,
            List<DiagnosisSuggestion> suggestions,
            List<String> suggestedTests
    ) {

        /**
         * Check if this result requires immediate attention.
         */
        public boolean isEmergency() {
            return acuityLevel == AcuityLevel.EMERGENCY || alertLevel == AlertLevel.LEVEL_3;
        }

        /**
         * Check if protocols were activated.
         */
        public boolean hasProtocols() {
            return !activatedProtocols.isEmpty();
        }

        /**
         * Get conditions that must be ruled out.
         */
        public List<DiagnosisSuggestion> getRuleOutConditions() {
            return suggestions.stream()
                    .filter(DiagnosisSuggestion::ruleOut)
                    .toList();
        }

        /**
         * Get likely conditions (not rule-out).
         */
        public List<DiagnosisSuggestion> getLikelyConditions() {
            return suggestions.stream()
                    .filter(s -> !s.ruleOut())
                    .toList();
        }

        /**
         * Create audit-safe representation.
         */
        public Map<String, Object> toAuditMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("policyVersion", policyVersion);
            map.put("evaluatedAt", evaluatedAt.toString());
            map.put("acuityLevel", acuityLevel.name());
            map.put("alertLevel", alertLevel.name());
            map.put("totalPoints", totalPoints);
            map.put("reasons", reasons);
            map.put("vitalViolationCount", vitalViolations.size());
            map.put("redFlagCount", redFlagResult.matches().size());
            map.put("clusterMatchCount", matchedClusters.size());
            map.put("protocolCount", activatedProtocols.size());
            map.put("suggestionCount", suggestions.size());
            return map;
        }
    }

}
