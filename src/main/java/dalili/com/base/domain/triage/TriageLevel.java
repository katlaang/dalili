package dalili.com.base.domain.triage;

/**
 * Triage priority levels based on Manchester Triage System (MTS).
 *
 * <p>The Manchester Triage System is widely used in emergency departments
 * to prioritize patients based on clinical urgency. Each level has a
 * target wait time and color code for visual identification.</p>
 *
 * <p>Priority order: RED (highest) → ORANGE → YELLOW → GREEN → BLUE (lowest)</p>
 *
 * @see <a href="https://www.triagenet.net/">Manchester Triage Group</a>
 */
public enum TriageLevel {

    /**
     * Immediate - Life threatening conditions requiring immediate intervention.
     * Target wait time: 0 minutes.
     *
     * <h4>Indicators:</h4>
     * <ul>
     *   <li>Cardiac/respiratory arrest</li>
     *   <li>Airway obstruction</li>
     *   <li>Unresponsive (AVPU = U)</li>
     *   <li>Severe respiratory distress</li>
     *   <li>Major trauma with active hemorrhage</li>
     *   <li>SpO2 < 90%</li>
     * </ul>
     */
    RED(1, 0, "Immediate", "#DC2626"),

    /**
     * Very Urgent - Serious conditions that could deteriorate rapidly.
     * Target wait time: 10 minutes.
     *
     * <h4>Indicators:</h4>
     * <ul>
     *   <li>Chest pain with cardiac history</li>
     *   <li>Stroke symptoms (FAST positive)</li>
     *   <li>Severe bleeding</li>
     *   <li>Severe asthma/COPD exacerbation</li>
     *   <li>SpO2 90-94%</li>
     *   <li>Altered mental status</li>
     *   <li>Temperature > 40°C</li>
     * </ul>
     */
    ORANGE(2, 10, "Very Urgent", "#EA580C"),

    /**
     * Urgent - Conditions requiring prompt attention but not immediately life threatening.
     * Target wait time: 60 minutes.
     *
     * <h4>Indicators:</h4>
     * <ul>
     *   <li>Moderate pain (4-7/10)</li>
     *   <li>High fever (38.5-40°C)</li>
     *   <li>Fractures without deformity</li>
     *   <li>Persistent vomiting</li>
     *   <li>Abdominal pain</li>
     *   <li>Hypertension > 180/110</li>
     * </ul>
     */
    YELLOW(3, 60, "Urgent", "#CA8A04"),

    /**
     * Standard - Non-urgent conditions that can safely wait.
     * Target wait time: 120 minutes.
     *
     * <h4>Indicators:</h4>
     * <ul>
     *   <li>Mild pain (1-3/10)</li>
     *   <li>Low-grade fever (37.5-38.5°C)</li>
     *   <li>Minor injuries</li>
     *   <li>Cold/flu symptoms</li>
     *   <li>Stable chronic conditions</li>
     * </ul>
     */
    GREEN(4, 120, "Standard", "#16A34A"),

    /**
     * Non-Urgent - Minor conditions or administrative visits.
     * Target wait time: 240 minutes.
     *
     * <h4>Indicators:</h4>
     * <ul>
     *   <li>Prescription refills</li>
     *   <li>Medical certificates</li>
     *   <li>Minor skin conditions</li>
     *   <li>Routine follow-up</li>
     * </ul>
     */
    BLUE(5, 240, "Non-Urgent", "#2563EB");

    private final int priority;
    private final int targetWaitMinutes;
    private final String displayName;
    private final String colorHex;

    TriageLevel(int priority, int targetWaitMinutes, String displayName, String colorHex) {
        this.priority = priority;
        this.targetWaitMinutes = targetWaitMinutes;
        this.displayName = displayName;
        this.colorHex = colorHex;
    }

    /**
     * Returns the priority order (1 = highest, 5 = lowest).
     *
     * @return priority value
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns the target wait time in minutes per MTS guidelines.
     *
     * @return target wait in minutes
     */
    public int getTargetWaitMinutes() {
        return targetWaitMinutes;
    }

    /**
     * Returns the human-readable display name.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the color hex code for UI display.
     *
     * @return color hex code
     */
    public String getColorHex() {
        return colorHex;
    }
}