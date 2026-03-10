package dalili.com.base.domain.escalation;

import dalili.com.base.domain.policy.AlertLevel;
import dalili.com.base.domain.policy.TriageRuleEngine;

import java.time.Instant;
import java.util.List;
import java.util.Random;

public class EscalationOrchestrator {

    private final TriageRuleEngine ruleEngine;

    public EscalationOrchestrator(TriageRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public EscalationResponse evaluate(TriageRuleEngine.TriageInput input) {
        TriageRuleEngine.TriageResult triageResult = ruleEngine.evaluate(input);
        return buildResponse(triageResult);
    }

    public EscalationResponse buildResponse(TriageRuleEngine.TriageResult triageResult) {
        return switch (triageResult.alertLevel()) {
            case NONE -> EscalationResponse.noAlert(triageResult);
            case LEVEL_1 -> buildLevel1Response(triageResult);
            case LEVEL_2 -> buildLevel2Response(triageResult);
            case LEVEL_3 -> buildLevel3Response(triageResult);
        };
    }

    private EscalationResponse buildLevel1Response(TriageRuleEngine.TriageResult result) {
        return new EscalationResponse(
                AlertLevel.LEVEL_1, result.policyVersion(), Instant.now(),
                "Review recommended", result.reasons(),
                result.suggestions().stream().limit(3).toList(),
                result.suggestedTests(), List.of(), false, null, result
        );
    }

    private EscalationResponse buildLevel2Response(TriageRuleEngine.TriageResult result) {
        return new EscalationResponse(
                AlertLevel.LEVEL_2, result.policyVersion(), Instant.now(),
                buildHeadline(result), result.reasons(),
                result.suggestions().stream().limit(5).toList(),
                result.suggestedTests(), List.of(), true, null, result
        );
    }

    private EscalationResponse buildLevel3Response(TriageRuleEngine.TriageResult result) {
        String incidentId = generateIncidentId();
        List<ProtocolInstance> protocols = result.activatedProtocols().stream()
                .map(p -> ProtocolInstance.create(p, incidentId))
                .toList();

        return new EscalationResponse(
                AlertLevel.LEVEL_3, result.policyVersion(), Instant.now(),
                buildUrgentHeadline(result), result.reasons(),
                result.getRuleOutConditions(), result.suggestedTests(),
                protocols, true, incidentId, result
        );
    }

    private String buildHeadline(TriageRuleEngine.TriageResult result) {
        if (result.redFlagResult().hasCriticalMatch()) return "Critical symptom detected";
        if (!result.vitalViolations().isEmpty()) return "Abnormal vital signs";
        return "Clinical review recommended";
    }

    private String buildUrgentHeadline(TriageRuleEngine.TriageResult result) {
        if (!result.activatedProtocols().isEmpty()) {
            return "URGENT: " + result.activatedProtocols().get(0).name();
        }
        return "URGENT: Immediate assessment required";
    }

    private String generateIncidentId() {
        String date = java.time.LocalDate.now().toString().replace("-", "");
        return "INC-" + date + "-" + String.format("%04d", new Random().nextInt(10000));
    }
}
