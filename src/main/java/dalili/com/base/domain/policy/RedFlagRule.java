package dalili.com.base.domain.policy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * RedFlagRule defines a phrase pattern with English and Swahili variants.
 */
public record RedFlagRule(
        String phrase,                    // Primary English phrase
        List<String> swahili,             // Swahili equivalents
        String category,
        List<String> suggests,
        RedFlagSeverity severity
) {

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public RedFlagRule {
        if (phrase == null || phrase.isBlank()) {
            throw new IllegalArgumentException("Phrase is required");
        }
        swahili = swahili != null ? List.copyOf(swahili) : List.of();
        suggests = suggests != null ? List.copyOf(suggests) : List.of();
    }

    // Convenience constructor without Swahili
    public RedFlagRule(String phrase, String category, List<String> suggests, RedFlagSeverity severity) {
        this(phrase, List.of(), category, suggests, severity);
    }

    /**
     * Check if any phrase variant (English or Swahili) matches the text.
     */
    public boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        // Normalize text: lowercase, remove punctuation.
        String normalizedText = normalizeText(text);

        // Check English phrase.
        if (matchesPhrase(normalizedText, phrase)) {
            return true;
        }

        // Check Swahili phrases.
        for (String swahiliPhrase : swahili) {
            if (matchesPhrase(normalizedText, swahiliPhrase)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesPhrase(String normalizedText, String phraseToMatch) {
        String normalizedPhrase = normalizeText(phraseToMatch);

        Pattern pattern = PATTERN_CACHE.computeIfAbsent(normalizedPhrase, p -> {
            String escaped = Pattern.quote(p);
            return Pattern.compile("\\b" + escaped + "\\b", Pattern.CASE_INSENSITIVE);
        });

        return pattern.matcher(normalizedText).find();
    }

    private String normalizeText(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s\\u00C0-\\u024F]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public int getEscalationPoints() {
        return switch (severity) {
            case CRITICAL -> 3;
            case HIGH -> 2;
            case MODERATE -> 1;
        };
    }

    /**
     * Get all phrase variants for display/logging.
     */
    public List<String> getAllPhraseVariants() {
        var all = new java.util.ArrayList<String>();
        all.add(phrase);
        all.addAll(swahili);
        return all;
    }
}
