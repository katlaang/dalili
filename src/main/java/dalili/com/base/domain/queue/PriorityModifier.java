package dalili.com.base.domain.queue;

/**
 * Additional priority modifiers based on patient characteristics.
 *
 * <p>These modifiers adjust queue position within a triage level
 * to ensure vulnerable populations receive timely care. They are
 * additive to the base triage level priority.</p>
 *
 * <p>Priority boost values are subtracted from the base priority,
 * so higher boost = higher priority (seen sooner).</p>
 */
public enum PriorityModifier {

    /**
     * No special priority modifier.
     */
    NONE(0, "Standard"),

    /**
     * Infant or toddler (under 5 years).
     *
     * <p>Young children can deteriorate quickly and may not
     * communicate symptoms clearly. Highest age-based priority.</p>
     */
    PEDIATRIC_INFANT(3, "Infant/Toddler (<5 years)"),

    /**
     * Child (5-12 years).
     */
    PEDIATRIC_CHILD(2, "Child (5-12 years)"),

    /**
     * Elderly patient (65+ years).
     *
     * <p>Higher risk of complications and atypical presentations.
     * May have multiple comorbidities.</p>
     */
    ELDERLY(2, "Elderly (65+ years)"),

    /**
     * Pregnant patient.
     *
     * <p>Two lives at stake; pregnancy can mask serious conditions.
     * Physiological changes affect normal vital sign ranges.</p>
     */
    PREGNANT(2, "Pregnant"),

    /**
     * Patient with disability requiring accommodation.
     */
    DISABILITY(1, "Disability Accommodation"),

    /**
     * Arrived by ambulance - already assessed by paramedics.
     *
     * <p>Paramedics have already determined need for hospital care.
     * Patient should proceed directly to triage assessment.</p>
     */
    AMBULANCE_ARRIVAL(3, "Ambulance Arrival"),

    /**
     * Patient condition deteriorating while waiting.
     *
     * <p>Applied when a waiting patient's condition worsens.
     * Requires immediate re-triage and priority escalation.</p>
     */
    DETERIORATING(4, "Condition Deteriorating");

    private final int priorityBoost;
    private final String displayName;

    PriorityModifier(int priorityBoost, String displayName) {
        this.priorityBoost = priorityBoost;
        this.displayName = displayName;
    }

    /**
     * Returns the priority boost value.
     *
     * <p>Higher values result in higher priority (seen sooner)
     * within the same triage level.</p>
     *
     * @return priority boost (0-4)
     */
    public int getPriorityBoost() {
        return priorityBoost;
    }

    /**
     * Returns the human-readable display name.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }
}