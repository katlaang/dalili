package dalili.com.base.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DifferentialService generates AI-assisted differential diagnoses.
 * <p>
 * Output includes:
 * - ICD-10 codes
 * - Confidence levels (High/Moderate/Low)
 * - Rule-out flags (critical to exclude)
 * - Suggested tests
 * <p>
 * NEVER auto-populates diagnosis - physician must explicitly select.
 */
public class DifferentialService {

    private static final Logger log = LoggerFactory.getLogger(DifferentialService.class);

    private static final String SYSTEM_PROMPT = """
            You are a clinical decision support assistant for healthcare professionals.
            Generate differential diagnoses based on patient presentation.
            
            IMPORTANT:
            - These are SUGGESTIONS only, not diagnoses
            - Include conditions that MUST be ruled out first (life-threatening)
            - Be conservative with confidence levels
            - Include appropriate diagnostic tests
            
            Respond in JSON format ONLY:
            {
              "differentials": [
                {
                  "icd10": "I21.9",
                  "name": "Acute myocardial infarction",
                  "confidence": "high",
                  "ruleOut": true,
                  "reasoning": "Classic chest pain with diaphoresis",
                  "suggestedTests": ["ECG", "Troponin", "CXR"]
                }
              ],
              "urgentConsiderations": ["Consider MI until ruled out"],
              "additionalHistory": ["Duration of symptoms", "Previous cardiac history"]
            }
            
            Confidence levels: high (>70%), moderate (40-70%), low (<40%)
            List 5-7 differentials, prioritize rule-out conditions first.
            """;

    private final LlmProviderAdapter llmProvider;
    private final ConnectivityMonitor connectivityMonitor;
    private final ObjectMapper objectMapper;

    public DifferentialService(LlmProviderAdapter llmProvider, ConnectivityMonitor connectivityMonitor) {
        this.llmProvider = llmProvider;
        this.connectivityMonitor = connectivityMonitor;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generate differential diagnoses from clinical data.
     * Returns empty result if AI unavailable (graceful degradation).
     */
    public DifferentialResult generate(DifferentialInput input) {
        if (!connectivityMonitor.isAiAvailable()) {
            log.debug("AI unavailable, returning empty differential");
            return DifferentialResult.unavailable();
        }

        try {
            String userPrompt = buildPrompt(input);

            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.2)  // Low temperature for clinical accuracy
                    .maxTokens(2048)
                    .build();

            LlmResponse response = llmProvider.complete(request);

            if (response.success()) {
                connectivityMonitor.recordSuccess();
                return parseResponse(response);
            } else {
                connectivityMonitor.recordFailure();
                log.warn("Differential generation failed: {}", response.errorMessage());
                return DifferentialResult.error(response.errorMessage());
            }

        } catch (Exception e) {
            connectivityMonitor.recordFailure();
            log.error("Differential generation error", e);
            return DifferentialResult.error(e.getMessage());
        }
    }

    private String buildPrompt(DifferentialInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("Patient: ").append(input.age()).append(" year old ").append(input.sex()).append("\n\n");

        if (input.chiefComplaint() != null) {
            sb.append("Chief Complaint: ").append(input.chiefComplaint()).append("\n\n");
        }

        if (input.vitals() != null) {
            sb.append("Vitals:\n").append(input.vitals()).append("\n\n");
        }

        if (input.symptoms() != null && !input.symptoms().isEmpty()) {
            sb.append("Symptoms: ").append(String.join(", ", input.symptoms())).append("\n\n");
        }

        if (input.history() != null) {
            sb.append("History: ").append(input.history()).append("\n\n");
        }

        if (input.physicalExam() != null) {
            sb.append("Physical Exam: ").append(input.physicalExam()).append("\n\n");
        }

        sb.append("Generate differential diagnoses with ICD-10 codes.");
        return sb.toString();
    }

    private DifferentialResult parseResponse(LlmResponse response) {
        try {
            // Extract JSON from response (may be wrapped in markdown)
            String content = response.content().trim();
            if (content.startsWith("```json")) {
                content = content.substring(7);
            }
            if (content.startsWith("```")) {
                content = content.substring(3);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            content = content.trim();

            JsonNode root = objectMapper.readTree(content);

            List<DifferentialDiagnosis> differentials = new ArrayList<>();
            JsonNode diffArray = root.path("differentials");
            for (JsonNode node : diffArray) {
                differentials.add(new DifferentialDiagnosis(
                        node.path("icd10").asText(),
                        node.path("name").asText(),
                        parseConfidence(node.path("confidence").asText()),
                        node.path("ruleOut").asBoolean(false),
                        node.path("reasoning").asText(),
                        parseStringList(node.path("suggestedTests"))
                ));
            }

            List<String> urgentConsiderations = parseStringList(root.path("urgentConsiderations"));
            List<String> additionalHistory = parseStringList(root.path("additionalHistory"));

            return new DifferentialResult(
                    true,
                    differentials,
                    urgentConsiderations,
                    additionalHistory,
                    response.provider(),
                    response.model(),
                    response.latencyMs(),
                    Instant.now(),
                    null
            );

        } catch (Exception e) {
            log.error("Failed to parse differential response", e);
            return DifferentialResult.error("Failed to parse AI response: " + e.getMessage());
        }
    }

    private ConfidenceLevel parseConfidence(String level) {
        return switch (level.toLowerCase()) {
            case "high" -> ConfidenceLevel.HIGH;
            case "moderate" -> ConfidenceLevel.MODERATE;
            default -> ConfidenceLevel.LOW;
        };
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }
}

