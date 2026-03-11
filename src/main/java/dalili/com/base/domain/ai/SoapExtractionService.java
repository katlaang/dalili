package dalili.com.base.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * SoapExtractionService extracts structured SOAP notes from transcripts.
 * <p>
 * SOAP = Subjective, Objective, Assessment, Plan
 * <p>
 * This is a DRAFT - physician must review and confirm.
 */
public class SoapExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SoapExtractionService.class);

    private static final String SYSTEM_PROMPT = """
            You are a medical documentation assistant. Extract a structured SOAP note from the clinical transcript.
            
            SOAP Format:
            - Subjective: Patient's complaints, history, symptoms in their words
            - Objective: Vital signs, physical exam findings, test results
            - Assessment: Clinical impression (do NOT diagnose - note findings only)
            - Plan: Discussed treatments, referrals, follow-up
            
            IMPORTANT:
            - Extract only what was discussed, do not infer or add
            - Use professional medical terminology
            - Flag any unclear or ambiguous information
            - This is a DRAFT for physician review
            
            Respond in JSON format ONLY:
            {
              "subjective": "Patient reports...",
              "objective": "Vitals: ... Physical exam: ...",
              "assessment": "Clinical findings suggest...",
              "plan": "1. ... 2. ...",
              "extractedSymptoms": ["symptom1", "symptom2"],
              "extractedVitals": {"bp": "120/80", "hr": "72"},
              "flaggedItems": ["Unclear duration of symptoms"],
              "confidence": "high"
            }
            """;

    private final LlmProviderAdapter llmProvider;
    private final ConnectivityMonitor connectivityMonitor;
    private final ObjectMapper objectMapper;

    public SoapExtractionService(LlmProviderAdapter llmProvider, ConnectivityMonitor connectivityMonitor) {
        this.llmProvider = llmProvider;
        this.connectivityMonitor = connectivityMonitor;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Extract SOAP note from transcript.
     * Returns empty result if AI unavailable.
     */
    public SoapExtractionResult extract(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return SoapExtractionResult.error("Empty transcript");
        }

        if (!connectivityMonitor.isAiAvailable()) {
            log.debug("AI unavailable, SOAP extraction skipped");
            return SoapExtractionResult.unavailable();
        }

        try {
            LlmRequest request = LlmRequest.builder()
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt("Extract SOAP note from this clinical transcript:\n\n" + transcript)
                    .temperature(0.1)  // Very low for accuracy
                    .maxTokens(2048)
                    .build();

            LlmResponse response = llmProvider.complete(request);

            if (response.success()) {
                connectivityMonitor.recordSuccess();
                return parseResponse(response);
            } else {
                connectivityMonitor.recordFailure();
                return SoapExtractionResult.error(response.errorMessage());
            }

        } catch (Exception e) {
            connectivityMonitor.recordFailure();
            log.error("SOAP extraction error", e);
            return SoapExtractionResult.error(e.getMessage());
        }
    }

    /**
     * Extracts a formatted SOAP draft suitable for clinician review screens.
     */
    public DraftResult extractDraft(String transcript) {
        SoapExtractionResult result = extract(transcript);
        if (!result.available() || result.soapNote() == null) {
            return DraftResult.unavailable(result.errorMessage());
        }

        return new DraftResult(
                true,
                result.soapNote().toFormattedString(),
                result.extractedSymptoms(),
                result.flaggedItems(),
                result.confidence(),
                result.provider(),
                result.model(),
                result.latencyMs(),
                result.generatedAt(),
                null
        );
    }

    private SoapExtractionResult parseResponse(LlmResponse response) {
        try {
            String content = response.content().trim();
            // Strip markdown code blocks if present
            if (content.startsWith("```json")) content = content.substring(7);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();

            JsonNode root = objectMapper.readTree(content);

            return new SoapExtractionResult(
                    true,
                    new SoapNote(
                            root.path("subjective").asText(),
                            root.path("objective").asText(),
                            root.path("assessment").asText(),
                            root.path("plan").asText()
                    ),
                    parseStringArray(root.path("extractedSymptoms")),
                    parseExtractedVitals(root.path("extractedVitals")),
                    parseStringArray(root.path("flaggedItems")),
                    root.path("confidence").asText("moderate"),
                    response.provider(),
                    response.model(),
                    response.latencyMs(),
                    Instant.now(),
                    null
            );

        } catch (Exception e) {
            log.error("Failed to parse SOAP response", e);
            return SoapExtractionResult.error("Parse error: " + e.getMessage());
        }
    }

    private java.util.List<String> parseStringArray(JsonNode node) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                list.add(item.asText());
            }
        }
        return list;
    }

    private ExtractedVitals parseExtractedVitals(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        return new ExtractedVitals(
                node.path("bp").asText(null),
                node.path("hr").asText(null),
                node.path("temp").asText(null),
                node.path("rr").asText(null),
                node.path("spo2").asText(null)
        );
    }

    public record DraftResult(
            boolean available,
            String draftNote,
            java.util.List<String> extractedSymptoms,
            java.util.List<String> flaggedItems,
            String confidence,
            String provider,
            String model,
            long latencyMs,
            Instant generatedAt,
            String errorMessage
    ) {
        public static DraftResult unavailable(String message) {
            return new DraftResult(
                    false,
                    null,
                    java.util.List.of(),
                    java.util.List.of(),
                    null,
                    null,
                    null,
                    0L,
                    Instant.now(),
                    message
            );
        }
    }
}

record SoapNote(
        String subjective,
        String objective,
        String assessment,
        String plan
) {
    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SUBJECTIVE:\n").append(subjective).append("\n\n");
        sb.append("OBJECTIVE:\n").append(objective).append("\n\n");
        sb.append("ASSESSMENT:\n").append(assessment).append("\n\n");
        sb.append("PLAN:\n").append(plan);
        return sb.toString();
    }
}

record ExtractedVitals(
        String bloodPressure,
        String heartRate,
        String temperature,
        String respiratoryRate,
        String oxygenSaturation
) {
}

record SoapExtractionResult(
        boolean available,
        SoapNote soapNote,
        java.util.List<String> extractedSymptoms,
        ExtractedVitals extractedVitals,
        java.util.List<String> flaggedItems,
        String confidence,
        String provider,
        String model,
        long latencyMs,
        Instant generatedAt,
        String errorMessage
) {
    public static SoapExtractionResult unavailable() {
        return new SoapExtractionResult(false, null, java.util.List.of(), null,
                java.util.List.of(), null, null, null, 0, Instant.now(),
                "AI service unavailable");
    }
    
    public static SoapExtractionResult error(String message) {
        return new SoapExtractionResult(false, null, java.util.List.of(), null,
                java.util.List.of(), null, null, null, 0, Instant.now(), message);
    }

    public boolean hasFlaggedItems() {
        return flaggedItems != null && !flaggedItems.isEmpty();
    }
}
