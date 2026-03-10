package dalili.com.base.domain.policy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

enum ClusterUrgency {EMERGENCY, URGENT, STANDARD, NON_URGENT}

/**
 * SymptomCluster defines a pattern of symptoms (English + Swahili) that
 * together suggest specific conditions and trigger appropriate urgency levels.
 */
public record SymptomCluster(
        String id,
        List<String> symptoms,           // English symptoms
        List<String> swahiliSymptoms,    // Swahili equivalents
        int minimumMatch,
        List<DiagnosisSuggestion> suggests,
        ClusterUrgency urgency,
        String protocol,
        List<String> tests,
        String ageGroup,
        String sex,
        boolean isolationRequired
) {

    public SymptomCluster {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Cluster ID required");
        if (symptoms == null || symptoms.isEmpty()) throw new IllegalArgumentException("Symptoms required");
        if (minimumMatch < 1 || minimumMatch > symptoms.size())
            throw new IllegalArgumentException("Invalid minimum_match");
        symptoms = List.copyOf(symptoms);
        swahiliSymptoms = swahiliSymptoms != null ? List.copyOf(swahiliSymptoms) : List.of();
        suggests = suggests != null ? List.copyOf(suggests) : List.of();
        tests = tests != null ? List.copyOf(tests) : List.of();
    }

    public ClusterMatchResult evaluate(Set<String> patientSymptoms, int ageYears, String patientSex) {
        if (ageGroup != null && !matchesAgeGroup(ageYears)) return ClusterMatchResult.noMatch(id);
        if (sex != null && !sex.equalsIgnoreCase(patientSex)) return ClusterMatchResult.noMatch(id);

        Set<String> normalized = patientSymptoms.stream()
                .map(String::toLowerCase).map(String::trim)
                .collect(Collectors.toSet());

        // Check both English and Swahili symptoms
        List<String> allClusterSymptoms = Stream.concat(
                symptoms.stream().map(String::toLowerCase),
                swahiliSymptoms.stream().map(String::toLowerCase)
        ).toList();

        List<String> matched = allClusterSymptoms.stream()
                .filter(s -> containsSymptom(normalized, s))
                .toList();

        // Count unique matches (don't double-count English/Swahili equivalents)
        int uniqueMatches = Math.min(matched.size(), symptoms.size());

        if (uniqueMatches >= minimumMatch) {
            return new ClusterMatchResult(id, true, matched, symptoms.size(), suggests,
                    urgency, protocol, tests, getEscalationPoints());
        }
        return ClusterMatchResult.noMatch(id);
    }

    private boolean containsSymptom(Set<String> patientSymptoms, String clusterSymptom) {
        if (patientSymptoms.contains(clusterSymptom)) return true;
        for (String ps : patientSymptoms) {
            if (ps.contains(clusterSymptom) || clusterSymptom.contains(ps)) return true;
        }
        return false;
    }

    private boolean matchesAgeGroup(int ageYears) {
        return switch (ageGroup.toLowerCase()) {
            case "infant" -> ageYears < 1;
            case "pediatric" -> ageYears >= 1 && ageYears < 14;
            case "adult" -> ageYears >= 14;
            default -> true;
        };
    }

    public int getEscalationPoints() {
        return switch (urgency) {
            case EMERGENCY -> 3;
            case URGENT -> 2;
            case STANDARD -> 1;
            case NON_URGENT -> 0;
        };
    }
}

record ClusterMatchResult(
        String clusterId, boolean matched, List<String> matchedSymptoms, int totalClusterSymptoms,
        List<DiagnosisSuggestion> suggests, ClusterUrgency urgency, String protocol,
        List<String> suggestedTests, int escalationPoints
) {
    public static ClusterMatchResult noMatch(String clusterId) {
        return new ClusterMatchResult(clusterId, false, List.of(), 0, List.of(),
                ClusterUrgency.NON_URGENT, null, List.of(), 0);
    }

    public double getMatchStrength() {
        return totalClusterSymptoms == 0 ? 0 : (double) matchedSymptoms.size() / totalClusterSymptoms;
    }

    public boolean hasRuleOutConditions() {
        return suggests.stream().anyMatch(DiagnosisSuggestion::ruleOut);
    }

    public List<DiagnosisSuggestion> getRuleOutConditions() {
        return suggests.stream().filter(DiagnosisSuggestion::ruleOut).toList();
    }
}
