package dalili.com.base.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Groq LLM provider using Llama 3.1 70B.
 * <p>
 * Free tier: 30 requests/minute
 * Cost: $0.59/1M input, $0.79/1M output tokens
 */
public class GroqProvider implements LlmProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GroqProvider.class);
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "llama-3.1-70b-versatile";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GroqProvider(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GroqProvider(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Groq API key is required");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            String requestBody = buildRequestBody(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            long latencyMs = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("Groq API error: {} - {}", response.statusCode(), response.body());
                return LlmResponse.error(getProviderName(), model,
                        "HTTP " + response.statusCode() + ": " + extractErrorMessage(response.body()),
                        latencyMs);
            }

            return parseResponse(response.body(), latencyMs);

        } catch (java.net.http.HttpTimeoutException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("Groq API timeout after {}ms", latencyMs);
            return LlmResponse.error(getProviderName(), model, "Request timeout", latencyMs);

        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("Groq API error", e);
            return LlmResponse.error(getProviderName(), model, e.getMessage(), latencyMs);
        }
    }

    @Override
    public CompletableFuture<LlmResponse> completeAsync(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> complete(request));
    }

    @Override
    public boolean isAvailable() {
        // Simple connectivity check
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.groq.com/"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Groq";
    }

    @Override
    public String getModelId() {
        return model;
    }

    private String buildRequestBody(LlmRequest request) throws Exception {
        var messages = new java.util.ArrayList<Map<String, String>>();

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "temperature", request.temperature(),
                "max_tokens", request.maxTokens()
        );

        return objectMapper.writeValueAsString(body);
    }

    private LlmResponse parseResponse(String responseBody, long latencyMs) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        String content = root.path("choices").get(0).path("message").path("content").asText();
        int inputTokens = root.path("usage").path("prompt_tokens").asInt();
        int outputTokens = root.path("usage").path("completion_tokens").asInt();

        return LlmResponse.success(content, getProviderName(), model,
                inputTokens, outputTokens, latencyMs);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("error").path("message").asText(responseBody);
        } catch (Exception e) {
            return responseBody;
        }
    }
}
