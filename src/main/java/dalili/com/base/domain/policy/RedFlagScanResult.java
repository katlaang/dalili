package dalili.com.base.domain.policy;

import java.util.List;

/**
 * Result of red flag phrase scanning.
 */
public record RedFlagScanResult(
        List<RedFlagMatch> matches,
        int totalPoints,
        RedFlagSeverity highestSeverity
) {

    public RedFlagScanResult {
        matches = matches != null ? List.copyOf(matches) : List.of();
    }

    public static RedFlagScanResult empty() {
        return new RedFlagScanResult(List.of(), 0, null);
    }

    public boolean hasMatches() {
        return !matches.isEmpty();
    }

    public boolean hasCriticalMatch() {
        return matches.stream().anyMatch(m -> m.severity() == RedFlagSeverity.CRITICAL);
    }

    public List<String> getAllSuggestedConditions() {
        return matches.stream()
                .flatMap(m -> m.suggests().stream())
                .distinct()
                .toList();
    }

    public List<String> getCategoriesMatched() {
        return matches.stream()
                .map(RedFlagMatch::category)
                .distinct()
                .toList();
    }
}
