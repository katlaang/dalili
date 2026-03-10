package dalili.com.base.domain.ai;

import java.util.Map;

/**
 * Request to LLM provider.
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens,
        Map<String, Object> metadata
) {
    public LlmRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User prompt is required");
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be 0-2");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String systemPrompt;
        private String userPrompt;
        private double temperature = 0.3;
        private int maxTokens = 2048;
        private Map<String, Object> metadata = Map.of();

        public Builder systemPrompt(String val) {
            this.systemPrompt = val;
            return this;
        }

        public Builder userPrompt(String val) {
            this.userPrompt = val;
            return this;
        }

        public Builder temperature(double val) {
            this.temperature = val;
            return this;
        }

        public Builder maxTokens(int val) {
            this.maxTokens = val;
            return this;
        }

        public Builder metadata(Map<String, Object> val) {
            this.metadata = val;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens, metadata);
        }
    }
}