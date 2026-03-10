package dalili.com.base.domain.ai;

import java.time.Instant;

/**
 * Response from LLM provider.
 */
public record LlmResponse(
        String content,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        long latencyMs,
        Instant completedAt,
        boolean success,
        String errorMessage
) {
    public static LlmResponse success(String content, String provider, String model,
                                      int inputTokens, int outputTokens, long latencyMs) {
        return new LlmResponse(content, provider, model, inputTokens, outputTokens,
                latencyMs, Instant.now(), true, null);
    }

    public static LlmResponse error(String provider, String model, String errorMessage, long latencyMs) {
        return new LlmResponse(null, provider, model, 0, 0, latencyMs, Instant.now(), false, errorMessage);
    }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}