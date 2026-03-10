package dalili.com.base.domain.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Abstract interface for LLM provider implementations.
 * Allows swapping between Groq, OpenAI, etc.
 */
public interface LlmProviderAdapter {

    /**
     * Send a completion request to the LLM.
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Async completion request.
     */
    CompletableFuture<LlmResponse> completeAsync(LlmRequest request);

    /**
     * Check if provider is available.
     */
    boolean isAvailable();

    /**
     * Get provider name for logging.
     */
    String getProviderName();

    /**
     * Get model identifier.
     */
    String getModelId();
}
