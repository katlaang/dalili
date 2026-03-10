package dalili.com.base.domain.policy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PolicyLoader parses YAML policy files into PolicyPackage objects.
 * <p>
 * Supports:
 * - Pediatric age bands (1-2, 3-5, 6-10, 11-13)
 * - Swahili phrase variants
 * - heuristic_weight instead of confidence
 */
public class PolicyLoader {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public PolicyPackage loadFromClasspath(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new PolicyLoadException("Policy file not found: " + resourcePath);
            }
            return loadFromInputStream(is);
        } catch (Exception e) {
            throw new PolicyLoadException("Failed to load policy from " + resourcePath, e);
        }
    }

    public PolicyPackage loadFromInputStream(InputStream inputStream) {
        Yaml yaml = new Yaml(new Constructor(new LoaderOptions()));
        Map<String, Object> root = yaml.load(inputStream);
        return parsePolicy(root);
    }

    public PolicyPackage loadFromString(String yamlContent) {
        Yaml yaml = new Yaml(new Constructor(new LoaderOptions()));
        Map<String, Object> root = yaml.load(yamlContent);
        return parsePolicy(root);
    }

    @SuppressWarnings("unchecked")
    private PolicyPackage parsePolicy(Map<String, Object> root) {
        return new PolicyPackage(
                getString(root, "version"),
                getString(root, "country"),
                getString(root, "country_name"),
                parseDate(getString(root, "effective_date")),
                parseDate(getString(root, "expires_date")),
                getString(root, "description"),
                parseVitalThresholds((Map<String, Object>) root.get("vital_thresholds")),
                parseRedFlagPhrases((Map<String, Object>) root.get("red_flag_phrases")),
                parseSymptomClusters((List<Map<String, Object>>) root.get("symptom_clusters")),
                parseProtocols((Map<String, Object>) root.get("protocols")),
                parseEscalationScoring((Map<String, Object>) root.get("escalation_scoring")),
                parseSuggestedTests((Map<String, Object>) root.get("suggested_tests")),
                parseMetadata((Map<String, Object>) root.get("metadata"))
        );
    }

    @SuppressWarnings("unchecked")
    private VitalThresholds parseVitalThresholds(Map<String, Object> map) {
        if (map == null) return null;

        return new VitalThresholds(
                parseAgeGroupThresholds((Map<String, Object>) map.get("adult")),
                parseAgeGroupThresholds((Map<String, Object>) map.get("pediatric_1_2")),
                parseAgeGroupThresholds((Map<String, Object>) map.get("pediatric_3_5")),
                parseAgeGroupThresholds((Map<String, Object>) map.get("pediatric_6_10")),
                parseAgeGroupThresholds((Map<String, Object>) map.get("pediatric_11_13")),
                parseAgeGroupThresholds((Map<String, Object>) map.get("infant"))
        );
    }

    @SuppressWarnings("unchecked")
    private AgeGroupThresholds parseAgeGroupThresholds(Map<String, Object> map) {
        if (map == null) return null;

        return new AgeGroupThresholds(
                parseThresholdTier((Map<String, Object>) map.get("emergency")),
                parseThresholdTier((Map<String, Object>) map.get("urgent")),
                parseThresholdTier((Map<String, Object>) map.get("standard"))
        );
    }

    private ThresholdTier parseThresholdTier(Map<String, Object> map) {
        if (map == null) return null;

        return new ThresholdTier(
                getInteger(map, "systolic_bp_below"),
                getInteger(map, "systolic_bp_above"),
                getInteger(map, "diastolic_bp_above"),
                getInteger(map, "oxygen_saturation_below"),
                getInteger(map, "heart_rate_above"),
                getInteger(map, "heart_rate_below"),
                getDouble(map, "temperature_above"),
                getDouble(map, "temperature_below"),
                getInteger(map, "respiratory_rate_above"),
                getInteger(map, "respiratory_rate_below"),
                getInteger(map, "gcs_below")
        );
    }

    @SuppressWarnings("unchecked")
    private RedFlagPhrases parseRedFlagPhrases(Map<String, Object> map) {
        if (map == null) return new RedFlagPhrases(List.of(), List.of(), List.of());

        return new RedFlagPhrases(
                parseRedFlagRules((List<Map<String, Object>>) map.get("critical"), RedFlagSeverity.CRITICAL),
                parseRedFlagRules((List<Map<String, Object>>) map.get("high"), RedFlagSeverity.HIGH),
                parseRedFlagRules((List<Map<String, Object>>) map.get("moderate"), RedFlagSeverity.MODERATE)
        );
    }

    @SuppressWarnings("unchecked")
    private List<RedFlagRule> parseRedFlagRules(List<Map<String, Object>> list, RedFlagSeverity severity) {
        if (list == null) return List.of();

        return list.stream()
                .map(item -> new RedFlagRule(
                        getString(item, "phrase"),
                        (List<String>) item.get("swahili"),  // May be null
                        getString(item, "category"),
                        (List<String>) item.get("suggests"),
                        severity
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<SymptomCluster> parseSymptomClusters(List<Map<String, Object>> list) {
        if (list == null) return List.of();

        return list.stream()
                .map(item -> new SymptomCluster(
                        getString(item, "id"),
                        (List<String>) item.get("symptoms"),
                        (List<String>) item.get("swahili_symptoms"),  // New: Swahili symptoms
                        getInteger(item, "minimum_match", 1),
                        parseDiagnosisSuggestions((List<Map<String, Object>>) item.get("suggests")),
                        parseClusterUrgency(getString(item, "urgency")),
                        getString(item, "protocol"),
                        (List<String>) item.get("tests"),
                        getString(item, "age_group"),
                        getString(item, "sex"),
                        getBoolean(item, "isolation_required", false)
                ))
                .toList();
    }

    private List<DiagnosisSuggestion> parseDiagnosisSuggestions(List<Map<String, Object>> list) {
        if (list == null) return List.of();

        return list.stream()
                .map(item -> new DiagnosisSuggestion(
                        getString(item, "code"),
                        getString(item, "name"),
                        getBoolean(item, "rule_out", false),
                        // Support both "confidence" (legacy) and "heuristic_weight" (new)
                        getDouble(item, "heuristic_weight", getDouble(item, "confidence", 0.5))
                ))
                .toList();
    }

    private ClusterUrgency parseClusterUrgency(String value) {
        if (value == null) return ClusterUrgency.NON_URGENT;
        try {
            return ClusterUrgency.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ClusterUrgency.NON_URGENT;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Protocol> parseProtocols(Map<String, Object> map) {
        if (map == null) return Map.of();

        Map<String, Protocol> protocols = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> protocolMap = (Map<String, Object>) entry.getValue();
            protocols.put(id, parseProtocol(id, protocolMap));
        }
        return Map.copyOf(protocols);
    }

    @SuppressWarnings("unchecked")
    private Protocol parseProtocol(String id, Map<String, Object> map) {
        return new Protocol(
                id,
                getString(map, "name"),
                parseClusterUrgency(getString(map, "urgency")),
                getBoolean(map, "time_critical", false),
                getBoolean(map, "region_specific", false),
                getString(map, "age_group"),
                parseProtocolSteps((List<Map<String, Object>>) map.get("steps"))
        );
    }

    @SuppressWarnings("unchecked")
    private List<ProtocolStep> parseProtocolSteps(List<Map<String, Object>> list) {
        if (list == null) return List.of();

        return list.stream()
                .map(item -> new ProtocolStep(
                        getString(item, "id"),
                        getString(item, "action"),
                        getBoolean(item, "required", false),
                        getBoolean(item, "conditional", false),
                        getInteger(item, "time_limit_minutes"),
                        (List<String>) item.get("contraindications"),
                        getString(item, "note"),
                        getString(item, "age_group")
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private EscalationScoring parseEscalationScoring(Map<String, Object> map) {
        if (map == null) return EscalationScoring.defaults();

        Map<String, Object> pointsMap = (Map<String, Object>) map.get("points");
        Map<String, Object> thresholdsMap = (Map<String, Object>) map.get("thresholds");

        PointValues points = pointsMap != null ? new PointValues(
                getInteger(pointsMap, "critical_vital", 3),
                getInteger(pointsMap, "high_risk_symptom", 2),
                getInteger(pointsMap, "moderate_concern", 1),
                getInteger(pointsMap, "critical_phrase", 3),
                getInteger(pointsMap, "high_phrase", 2),
                getInteger(pointsMap, "symptom_cluster_emergency", 3),
                getInteger(pointsMap, "symptom_cluster_urgent", 2)
        ) : PointValues.defaults();

        AlertThresholds thresholds = thresholdsMap != null ? new AlertThresholds(
                getInteger(thresholdsMap, "level_1_min", 1),
                getInteger(thresholdsMap, "level_1_max", 2),
                getInteger(thresholdsMap, "level_2_min", 3),
                getInteger(thresholdsMap, "level_2_max", 4),
                getInteger(thresholdsMap, "level_3_min", 5)
        ) : AlertThresholds.defaults();

        return new EscalationScoring(points, thresholds);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseSuggestedTests(Map<String, Object> map) {
        if (map == null) return Map.of();

        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            result.put(entry.getKey(), (List<String>) entry.getValue());
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private PolicyMetadata parseMetadata(Map<String, Object> map) {
        if (map == null) return new PolicyMetadata(null, null, null, null, null, List.of());

        return new PolicyMetadata(
                getString(map, "created_by"),
                getString(map, "alignment"),  // Changed from reviewed_by
                parseDate(getString(map, "last_updated")),
                parseDate(getString(map, "next_review")),
                (List<String>) map.get("version_notes"),
                (List<String>) map.get("references")
        );
    }

    // Helper methods
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        return Integer.parseInt(value.toString());
    }

    private Integer getInteger(Map<String, Object> map, String key, int defaultValue) {
        Integer value = getInteger(map, key);
        return value != null ? value : defaultValue;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    private Double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Double value = getDouble(map, key);
        return value != null ? value : defaultValue;
    }

    private Boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDate.parse(value, DATE_FORMATTER);
    }
}

class PolicyLoadException extends RuntimeException {
    public PolicyLoadException(String message) {
        super(message);
    }

    public PolicyLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
