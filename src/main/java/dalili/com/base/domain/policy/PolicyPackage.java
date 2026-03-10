package dalili.com.base.domain.policy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PolicyPackage represents a versioned, country-specific triage rule set.
 * <p>
 * Loaded from YAML at startup or sync. Executes locally (offline-capable).
 * Every encounter logs the policy version used for legal defensibility.
 */
public record PolicyPackage(
        String version,
        String country,
        String countryName,
        LocalDate effectiveDate,
        LocalDate expiresDate,
        String description,
        VitalThresholds vitalThresholds,
        RedFlagPhrases redFlagPhrases,
        List<SymptomCluster> symptomClusters,
        Map<String, Protocol> protocols,
        EscalationScoring escalationScoring,
        Map<String, List<String>> suggestedTests,
        PolicyMetadata metadata
) {

    public PolicyPackage {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Version required");
        if (country == null || country.isBlank()) throw new IllegalArgumentException("Country required");
        if (effectiveDate == null) throw new IllegalArgumentException("Effective date required");
    }

    public boolean isActive() {
        LocalDate today = LocalDate.now();
        return !today.isBefore(effectiveDate) && (expiresDate == null || !today.isAfter(expiresDate));
    }

    public boolean isExpired() {
        return expiresDate != null && LocalDate.now().isAfter(expiresDate);
    }

    public Optional<Protocol> getProtocol(String protocolId) {
        return Optional.ofNullable(protocols.get(protocolId));
    }

    public List<String> getSuggestedTests(String presentationType) {
        return suggestedTests.getOrDefault(presentationType, List.of());
    }

    /**
     * Get age-appropriate vital thresholds using the new age band structure.
     */
    public AgeGroupThresholds getThresholdsForAge(int ageYears) {
        if (vitalThresholds == null) return null;
        return vitalThresholds.getForAge(ageYears);
    }
}
