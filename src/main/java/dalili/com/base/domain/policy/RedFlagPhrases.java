package dalili.com.base.domain.policy;

import java.util.List;
import java.util.stream.Stream;

/**
 * RedFlagPhrases contains categorized phrases that trigger escalation.
 * <p>
 * Supports English and Swahili phrases for Kenya deployment.
 * Matching is case-insensitive with word boundary awareness.
 * Text normalization: lowercase, punctuation removed, token-based.
 */
public record RedFlagPhrases(
        List<RedFlagRule> critical,
        List<RedFlagRule> high,
        List<RedFlagRule> moderate
) {

    public RedFlagPhrases {
        critical = critical != null ? List.copyOf(critical) : List.of();
        high = high != null ? List.copyOf(high) : List.of();
        moderate = moderate != null ? List.copyOf(moderate) : List.of();
    }

    public List<RedFlagRule> getAllRules() {
        return Stream.concat(Stream.concat(critical.stream(), high.stream()), moderate.stream()).toList();
    }
}
