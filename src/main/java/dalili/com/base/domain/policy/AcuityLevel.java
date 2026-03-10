package dalili.com.base.domain.policy;

public enum AcuityLevel {
    EMERGENCY(1),
    URGENT(2),
    STANDARD(3),
    NON_URGENT(4);

    private final int priority;

    AcuityLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isMoreSevereThan(AcuityLevel other) {
        return this.priority < other.priority;
    }
}
