package dalili.com.base.application.service;

import dalili.com.base.domain.encounter.model.MedicationOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Lightweight contraindication/interactions helper for portal visibility.
 */
@Service
public class MedicationSafetyService {

    private static final Logger log = LoggerFactory.getLogger(MedicationSafetyService.class);

    public Map<UUID, List<String>> evaluateContraindications(List<MedicationOrder> medicationOrders, String allergies) {
        log.info("Evaluating medication safety for medicationCount={}", medicationOrders == null ? 0 : medicationOrders.size());
        Map<UUID, List<String>> warnings = new HashMap<>();
        Map<String, List<String>> interactionRules = buildInteractionRules();

        for (MedicationOrder order : medicationOrders) {
            List<String> orderWarnings = new ArrayList<>();
            String medKey = normalize(order.getMedicationName());
            List<String> interactionTargets = interactionRules.getOrDefault(medKey, List.of());

            for (MedicationOrder other : medicationOrders) {
                if (order.getId().equals(other.getId())) {
                    continue;
                }
                String otherName = normalize(other.getMedicationName());
                for (String target : interactionTargets) {
                    if (otherName.contains(target)) {
                        orderWarnings.add(order.getMedicationName() + " may interact with " + other.getMedicationName());
                    }
                }
            }

            if (allergies != null && !allergies.isBlank()) {
                String allergyText = allergies.toLowerCase(Locale.ROOT);
                if (allergyText.contains(medKey)) {
                    orderWarnings.add("Allergy warning: patient allergies mention " + order.getMedicationName());
                }
                if (allergyText.contains("penicillin") && medKey.contains("amoxicillin")) {
                    orderWarnings.add("Allergy warning: penicillin allergy may contraindicate " + order.getMedicationName());
                }
            }

            warnings.put(order.getId(), orderWarnings.stream().distinct().toList());
        }

        long interactions = warnings.values().stream().mapToLong(List::size).sum();
        log.info("Medication safety evaluation complete medicationCount={} warningCount={}", warnings.size(), interactions);
        return warnings;
    }

    private Map<String, List<String>> buildInteractionRules() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("aspirin", List.of("warfarin", "clopidogrel", "heparin"));
        rules.put("ibuprofen", List.of("warfarin", "lisinopril", "losartan"));
        rules.put("diclofenac", List.of("warfarin", "lisinopril", "losartan"));
        rules.put("amoxicillin/clavulanate", List.of("allopurinol", "warfarin"));
        rules.put("azithromycin", List.of("amiodarone", "warfarin", "digoxin"));
        rules.put("ciprofloxacin", List.of("warfarin", "theophylline", "tizanidine"));
        rules.put("metronidazole", List.of("warfarin", "alcohol"));
        rules.put("metformin", List.of("insulin", "glibenclamide", "gliclazide"));
        rules.put("amlodipine", List.of("simvastatin"));
        rules.put("enalapril", List.of("spironolactone", "potassium"));
        rules.put("losartan", List.of("spironolactone", "potassium"));
        rules.put("prednisolone", List.of("ibuprofen", "diclofenac"));
        return rules;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}



