package dalili.com.base.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * AmbientTranscriptionService converts captured audio into transcript text.
 *
 * <p>It uses Groq's speech-to-text endpoint and returns structured metadata
 * to support audit visibility and graceful degradation.</p>
 */
public class AmbientTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AmbientTranscriptionService.class);
    private static final String API_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String model;
    private final ConnectivityMonitor connectivityMonitor;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AmbientTranscriptionService(String apiKey, String model, ConnectivityMonitor connectivityMonitor) {
        this.apiKey = apiKey;
        this.model = model;
        this.connectivityMonitor = connectivityMonitor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public TranscriptionResult transcribe(
            byte[] audioBytes,
            String fileName,
            String mimeType,
            String language,
            String prompt
    ) {
        long startTime = System.currentTimeMillis();

        if (audioBytes == null || audioBytes.length == 0) {
            return TranscriptionResult.error("Audio payload is empty");
        }

        if (apiKey == null || apiKey.isBlank()) {
            return TranscriptionResult.unavailable("Groq API key not configured");
        }

        if (!connectivityMonitor.isAiAvailable()) {
            return TranscriptionResult.unavailable("AI service unavailable");
        }

        try {
            String boundary = "----DaliliBoundary" + UUID.randomUUID();
            byte[] requestBody = buildMultipartBody(
                    boundary,
                    audioBytes,
                    fileName != null && !fileName.isBlank() ? fileName : "ambient-audio.webm",
                    mimeType != null && !mimeType.isBlank() ? mimeType : "audio/webm",
                    language,
                    prompt
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                connectivityMonitor.recordFailure();
                String message = extractErrorMessage(response.body());
                log.warn("Ambient transcription failed: {} - {}", response.statusCode(), message);
                return TranscriptionResult.error("HTTP " + response.statusCode() + ": " + message, latencyMs);
            }

            JsonNode root = objectMapper.readTree(response.body());
            String transcript = root.path("text").asText();
            if (transcript == null || transcript.isBlank()) {
                connectivityMonitor.recordFailure();
                return TranscriptionResult.error("Transcription returned empty text", latencyMs);
            }

            connectivityMonitor.recordSuccess();
            return TranscriptionResult.success(
                    transcript,
                    "Groq",
                    model,
                    latencyMs
            );
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            connectivityMonitor.recordFailure();
            log.error("Ambient transcription error", e);
            return TranscriptionResult.error(e.getMessage(), latencyMs);
        }
    }

    private byte[] buildMultipartBody(
            String boundary,
            byte[] audioBytes,
            String fileName,
            String mimeType,
            String language,
            String prompt
    ) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writeFormField(output, boundary, "model", model);
        writeFormField(output, boundary, "response_format", "json");
        if (language != null && !language.isBlank()) {
            writeFormField(output, boundary, "language", language);
        }
        if (prompt != null && !prompt.isBlank()) {
            writeFormField(output, boundary, "prompt", prompt);
        }

        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(audioBytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));

        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private void writeFormField(ByteArrayOutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText();
            if (message != null && !message.isBlank()) {
                return message;
            }
            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }

    public record TranscriptionResult(
            boolean available,
            String transcript,
            String provider,
            String model,
            long latencyMs,
            Instant generatedAt,
            String errorMessage
    ) {
        public static TranscriptionResult success(String transcript, String provider, String model, long latencyMs) {
            return new TranscriptionResult(true, transcript, provider, model, latencyMs, Instant.now(), null);
        }

        public static TranscriptionResult unavailable(String message) {
            return new TranscriptionResult(false, null, null, null, 0L, Instant.now(), message);
        }

        public static TranscriptionResult error(String message) {
            return new TranscriptionResult(false, null, null, null, 0L, Instant.now(), message);
        }

        public static TranscriptionResult error(String message, long latencyMs) {
            return new TranscriptionResult(false, null, null, null, latencyMs, Instant.now(), message);
        }
    }
}
