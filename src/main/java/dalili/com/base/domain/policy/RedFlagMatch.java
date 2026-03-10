package dalili.com.base.domain.policy;

import java.util.List;

/**
 * A single matched red flag.
 */
public record RedFlagMatch(
        String phrase,
        String category,
        List<String> suggests,
        RedFlagSeverity severity,
        int points
) {
}
