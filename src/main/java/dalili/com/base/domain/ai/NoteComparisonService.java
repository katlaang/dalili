package dalili.com.base.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares physician final notes against ambient transcript and optional AI draft.
 * Falls back gracefully when AI is unavailable.
 */
public class NoteComparisonService {

    private static final Logger log = LoggerFactory.getLogger(NoteComparisonService.class);

    private static final String SYSTEM_PROMPT = """
            You are a clinical documentation quality assistant.
            Compare a physician final note against an ambient transcript and an optional AI draft.
            
            Respond with strict JSON only:
            {
              "rating": "ACCURATE|MINOR_EDITS|MAJOR_EDITS|UNSAFE",
              "score": 0,
              "summary": "short discrepancy summary",
              "discrepancies": ["item 1", "item 2"],
              "safetyFlags": ["critical mismatch 1"]
            }
            
            Rules:
            - Use only the provided text.
            - Focus on clinically meaningful omissions or contradictions.
            - If dangerous mismatches exist, rating must be UNSAFE.
            - Keep summary concise.
            """;

    private final LlmProviderAdapter llmProvider;
    private final ConnectivityMonitor connectivityMonitor;
    private final ObjectMapper objectMapper;

    public NoteComparisonService(LlmProviderAdapter llmProvider, ConnectivityMonitor connectivityMonitor) {
        this.llmProvider = llmProvider;
        this.connectivityMonitor = connectivityMonitor;
        this.objectMapper = new ObjectMapper();
    }

    public ComparisonResult compare(String transcript, String aiDraftNote, String finalNote) {
        if (finalNote == null || finalNote.isBlank()) {
            return ComparisonResult.error("Final note is empty");
        }
        if ((transcript == null || transcript.isBlank()) && (aiDraftNote == null || aiDraftNote.isBlank())) {
            return ComparisonResult.error("No source text available for comparison");
        }
        if (!connectivityMonitor.isAiAvailable()) {
            return ComparisonResult.unavailable("AI service unavailable");
        }

        try {
            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(buildPrompt(transcript, aiDraftNote, finalNote))
                    .temperature(0.1)
                    .maxTokens(1200)
                    .build();

            LlmResponse response = llmProvider.complete(request);
            if (!response.success()) {
                connectivityMonitor.recordFailure();
                return ComparisonResult.error(response.errorMessage());
            }

            connectivityMonitor.recordSuccess();
            return parseResponse(response);
        } catch (Exception e) {
            connectivityMonitor.recordFailure();
            log.error("Note comparison error", e);
            return ComparisonResult.error(e.getMessage());
        }
    }

    private ComparisonResult parseResponse(LlmResponse response) {
        try {
            String content = stripCodeFences(response.content());
            JsonNode root = objectMapper.readTree(content);

            ComparisonRating rating = parseRating(root.path("rating").asText());
            int score = clampScore(root.path("score").asInt(0));
            String summary = root.path("summary").asText("No discrepancy summary returned by AI");
            List<String> discrepancies = parseStringList(root.path("discrepancies"));
            List<String> safetyFlags = parseStringList(root.path("safetyFlags"));

            return new ComparisonResult(
                    true,
                    rating,
                    score,
                    summary,
                    discrepancies,
                    safetyFlags,
                    response.provider(),
                    response.model(),
                    response.latencyMs(),
                    Instant.now(),
                    null
            );
        } catch (Exception e) {
            log.error("Failed to parse note comparison response", e);
            return ComparisonResult.error("Failed to parse AI comparison response: " + e.getMessage());
        }
    }

    private String buildPrompt(String transcript, String aiDraftNote, String finalNote) {
        StringBuilder sb = new StringBuilder();
        if (transcript != null && !transcript.isBlank()) {
            sb.append("AMBIENT_TRANSCRIPT:\n");
            sb.append(truncateText(transcript, 9000)).append("\n\n");
        }
        if (aiDraftNote != null && !aiDraftNote.isBlank()) {
            sb.append("AI_DRAFT_NOTE:\n");
            sb.append(truncateText(aiDraftNote, 5000)).append("\n\n");
        }
        sb.append("PHYSICIAN_FINAL_NOTE:\n");
        sb.append(truncateText(finalNote, 5000)).append("\n\n");
        sb.append("Return JSON only.");
        return sb.toString();
    }

    private String stripCodeFences(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```json")) {
            normalized = normalized.substring(7);
        } else if (normalized.startsWith("```")) {
            normalized = normalized.substring(3);
        }
        if (normalized.endsWith("```")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.trim();
    }

    private ComparisonRating parseRating(String rawRating) {
        String normalized = rawRating == null ? "" : rawRating.trim().toUpperCase();
        return switch (normalized) {
            case "ACCURATE" -> ComparisonRating.ACCURATE;
            case "MINOR_EDITS" -> ComparisonRating.MINOR_EDITS;
            case "MAJOR_EDITS" -> ComparisonRating.MAJOR_EDITS;
            case "UNSAFE" -> ComparisonRating.UNSAFE;
            default -> ComparisonRating.NOT_ASSESSED;
        };
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    public enum ComparisonRating {
        ACCURATE,
        MINOR_EDITS,
        MAJOR_EDITS,
        UNSAFE,
        NOT_ASSESSED
    }

    public record ComparisonResult(
            boolean available,
            ComparisonRating rating,
            Integer score,
            String summary,
            List<String> discrepancies,
            List<String> safetyFlags,
            String provider,
            String model,
            long latencyMs,
            Instant generatedAt,
            String errorMessage
    ) {
        public static ComparisonResult unavailable(String message) {
            return new ComparisonResult(
                    false,
                    ComparisonRating.NOT_ASSESSED,
                    null,
                    "AI comparison unavailable",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    0L,
                    Instant.now(),
                    message
            );
        }

        public static ComparisonResult error(String message) {
            return new ComparisonResult(
                    false,
                    ComparisonRating.NOT_ASSESSED,
                    null,
                    "AI comparison failed",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    0L,
                    Instant.now(),
                    message
            );
        }
    }
}
