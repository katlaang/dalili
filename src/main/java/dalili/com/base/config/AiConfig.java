package dalili.com.base.config;

import dalili.com.base.domain.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for AI services.
 * <p>
 * Requires in application.yml:
 * dalili:
 * ai:
 * groq-api-key: ${GROQ_API_KEY}
 * model: llama-3.1-70b-versatile  # optional
 * enabled: true
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${dalili.ai.groq_api_key:}")
    private String groqApiKey;

    @Value("${dalili.ai.model:llama-3.1-70b-versatile}")
    private String model;

    @Value("${dalili.ai.enabled:true}")
    private boolean aiEnabled;

    @Bean
    public ConnectivityMonitor connectivityMonitor() {
        ConnectivityMonitor monitor = new ConnectivityMonitor();

        // If AI disabled, force offline
        if (!aiEnabled) {
            log.info("AI features disabled by configuration");
            monitor.setOverride(ConnectivityMonitor.ModeOverride.FORCE_OFF);
        }

        return monitor;
    }

    @Bean
    public LlmProviderAdapter llmProvider(ConnectivityMonitor connectivityMonitor) {
        if (groqApiKey == null || groqApiKey.isBlank()) {
            log.warn("Groq API key not configured - AI features will be unavailable");
            connectivityMonitor.setOverride(ConnectivityMonitor.ModeOverride.FORCE_OFF);
            return new NoOpLlmProvider();
        }

        log.info("Groq provider initialized with model: {}", model);
        return new GroqProvider(groqApiKey, model);
    }

    @Bean
    public DifferentialService differentialService(
            LlmProviderAdapter llmProvider,
            ConnectivityMonitor connectivityMonitor) {
        return new DifferentialService(llmProvider, connectivityMonitor);
    }

    @Bean
    public SoapExtractionService soapExtractionService(
            LlmProviderAdapter llmProvider,
            ConnectivityMonitor connectivityMonitor) {
        return new SoapExtractionService(llmProvider, connectivityMonitor);
    }

    /**
     * No-op provider when API key not configured.
     */
    private static class NoOpLlmProvider implements LlmProviderAdapter {
        @Override
        public LlmResponse complete(LlmRequest request) {
            return LlmResponse.error("NoOp", "none", "AI not configured", 0);
        }

        @Override
        public java.util.concurrent.CompletableFuture<LlmResponse> completeAsync(LlmRequest request) {
            return java.util.concurrent.CompletableFuture.completedFuture(complete(request));
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public String getProviderName() {
            return "NoOp";
        }

        @Override
        public String getModelId() {
            return "none";
        }
    }
}
