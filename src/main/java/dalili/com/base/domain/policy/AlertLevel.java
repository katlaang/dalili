package dalili.com.base.domain.policy;

import lombok.Getter;

/**
 * Alert levels with associated behaviors.
 */
public enum AlertLevel {
    NONE(0, "No alert", false, false, false),
    LEVEL_1(1, "Passive notification", false, false, false),
    LEVEL_2(2, "Soft interrupt", true, false, false),
    LEVEL_3(3, "Hard interrupt", true, true, true);

    @Getter
    private final int level;
    @Getter
    private final String description;
    private final boolean requiresAcknowledgment;
    private final boolean showsProtocol;
    private final boolean blocksWorkflow;

    AlertLevel(int level, String description, boolean requiresAcknowledgment,
               boolean showsProtocol, boolean blocksWorkflow) {
        this.level = level;
        this.description = description;
        this.requiresAcknowledgment = requiresAcknowledgment;
        this.showsProtocol = showsProtocol;
        this.blocksWorkflow = blocksWorkflow;
    }

    public boolean requiresAcknowledgment() {
        return requiresAcknowledgment;
    }

    public boolean showsProtocol() {
        return showsProtocol;
    }

    public boolean blocksWorkflow() {
        return blocksWorkflow;
    }

    public boolean isMoreSevereThan(AlertLevel other) {
        return this.level > other.level;
    }
}
