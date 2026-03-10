package dalili.com.base.domain.escalation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StepInstance {

    private final String stepId;
    private final String action;
    private final boolean required;
    private final Integer timeLimitMinutes;
    private final List<String> contraindications;

    private boolean completed;
    private boolean skipped;
    private Instant completedAt;
    private String completedBy;
    private String notes;
    private String skipReason;

    public StepInstance(String stepId, String action, boolean required,
                        Integer timeLimitMinutes, List<String> contraindications) {
        this.stepId = stepId;
        this.action = action;
        this.required = required;
        this.timeLimitMinutes = timeLimitMinutes;
        this.contraindications = contraindications != null ? List.copyOf(contraindications) : List.of();
    }

    public String getStepId() {
        return stepId;
    }

    public String getAction() {
        return action;
    }

    public boolean isRequired() {
        return required;
    }

    public Integer getTimeLimitMinutes() {
        return timeLimitMinutes;
    }

    public List<String> getContraindications() {
        return contraindications;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public String getNotes() {
        return notes;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public StepCompletion complete(String completedBy, String notes) {
        if (this.completed || this.skipped) {
            throw new IllegalStateException("Step already completed or skipped");
        }
        this.completed = true;
        this.completedAt = Instant.now();
        this.completedBy = completedBy;
        this.notes = notes;
        return new StepCompletion(stepId, completedBy, completedAt, notes, false, null);
    }

    public StepCompletion skip(String skippedBy, String reason) {
        if (this.completed || this.skipped) {
            throw new IllegalStateException("Step already completed or skipped");
        }
        this.skipped = true;
        this.completedAt = Instant.now();
        this.completedBy = skippedBy;
        this.skipReason = reason;
        return new StepCompletion(stepId, skippedBy, completedAt, null, true, reason);
    }

    public boolean isOverdue(Instant protocolStartTime) {
        if (completed || skipped || timeLimitMinutes == null) return false;
        Instant deadline = protocolStartTime.plusSeconds(timeLimitMinutes * 60L);
        return Instant.now().isAfter(deadline);
    }

    public Map<String, Object> toAuditMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepId", stepId);
        map.put("action", action);
        map.put("required", required);
        map.put("completed", completed);
        map.put("skipped", skipped);
        if (completedAt != null) map.put("completedAt", completedAt.toString());
        if (completedBy != null) map.put("completedBy", completedBy);
        if (notes != null) map.put("notes", notes);
        if (skipReason != null) map.put("skipReason", skipReason);
        return map;
    }
}
