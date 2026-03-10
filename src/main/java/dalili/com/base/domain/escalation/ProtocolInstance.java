package dalili.com.base.domain.escalation;

import dalili.com.base.domain.policy.Protocol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProtocolInstance {

    private final String protocolId;
    private final String protocolName;
    private final String incidentId;
    private final Instant createdAt;
    private final List<StepInstance> steps;

    private ProtocolInstance(String protocolId, String protocolName,
                             String incidentId, List<StepInstance> steps) {
        this.protocolId = protocolId;
        this.protocolName = protocolName;
        this.incidentId = incidentId;
        this.createdAt = Instant.now();
        this.steps = new ArrayList<>(steps);
    }

    public static ProtocolInstance create(Protocol protocol, String incidentId) {
        List<StepInstance> steps = protocol.steps().stream()
                .map(step -> new StepInstance(
                        step.id(), step.action(), step.required(),
                        step.timeLimitMinutes(), step.contraindications()
                ))
                .toList();
        return new ProtocolInstance(protocol.id(), protocol.name(), incidentId, steps);
    }

    public String getProtocolId() {
        return protocolId;
    }

    public String getProtocolName() {
        return protocolName;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<StepInstance> getSteps() {
        return List.copyOf(steps);
    }

    public StepCompletion completeStep(String stepId, String completedBy, String notes) {
        for (StepInstance step : steps) {
            if (step.getStepId().equals(stepId)) {
                return step.complete(completedBy, notes);
            }
        }
        throw new IllegalArgumentException("Step not found: " + stepId);
    }

    public StepCompletion skipStep(String stepId, String skippedBy, String reason) {
        for (StepInstance step : steps) {
            if (step.getStepId().equals(stepId)) {
                return step.skip(skippedBy, reason);
            }
        }
        throw new IllegalArgumentException("Step not found: " + stepId);
    }

    public boolean isComplete() {
        return steps.stream().filter(StepInstance::isRequired)
                .allMatch(s -> s.isCompleted() || s.isSkipped());
    }

    public double getCompletionPercentage() {
        long total = steps.stream().filter(StepInstance::isRequired).count();
        if (total == 0) return 100.0;
        long done = steps.stream().filter(StepInstance::isRequired)
                .filter(s -> s.isCompleted() || s.isSkipped()).count();
        return (done * 100.0) / total;
    }

    public List<StepInstance> getOverdueSteps() {
        return steps.stream()
                .filter(s -> !s.isCompleted() && !s.isSkipped() && s.isOverdue(createdAt))
                .toList();
    }

    public Map<String, Object> toAuditMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("protocolId", protocolId);
        map.put("protocolName", protocolName);
        map.put("incidentId", incidentId);
        map.put("createdAt", createdAt.toString());
        map.put("completionPercentage", getCompletionPercentage());
        map.put("isComplete", isComplete());
        map.put("steps", steps.stream().map(StepInstance::toAuditMap).toList());
        return map;
    }
}
