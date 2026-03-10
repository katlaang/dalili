package dalili.com.base.domain.escalation;

import dalili.com.base.domain.policy.AlertLevel;
import dalili.com.base.domain.policy.DiagnosisSuggestion;
import dalili.com.base.domain.policy.TriageRuleEngine;

import java.time.Instant;
import java.util.List;

public record EscalationResponse(
        AlertLevel alertLevel,
        String policyVersion,
        Instant evaluatedAt,
        String headline,
        List<String> reasons,
        List<DiagnosisSuggestion> suggestions,
        List<String> suggestedTests,
        List<ProtocolInstance> protocols,
        boolean requiresAcknowledgment,
        String incidentId,
        TriageRuleEngine.TriageResult triageResult
) {

    public static EscalationResponse noAlert(TriageRuleEngine.TriageResult triageResult) {
        return new EscalationResponse(
                AlertLevel.NONE, triageResult.policyVersion(), Instant.now(),
                null, List.of(), List.of(), List.of(), List.of(),
                false, null, triageResult
        );
    }

    public boolean hasAlert() {
        return alertLevel != AlertLevel.NONE;
    }

    public boolean isEmergency() {
        return alertLevel == AlertLevel.LEVEL_3;
    }

    public boolean hasProtocols() {
        return !protocols.isEmpty();
    }
}
