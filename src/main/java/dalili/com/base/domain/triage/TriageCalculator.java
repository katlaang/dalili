package dalili.com.base.domain.triage;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Calculates suggested triage level based on vital signs and clinical indicators.
 *
 * <p>This component implements evidence-based triage algorithms that analyze
 * patient vital signs and presenting symptoms to suggest an appropriate
 * triage level. The suggestions are based on:</p>
 *
 * <ul>
 *   <li>Manchester Triage System (MTS) guidelines</li>
 *   <li>Pediatric and geriatric-specific thresholds</li>
 *   <li>Red flag symptoms requiring immediate attention</li>
 * </ul>
 *
 * <p><strong>Important:</strong> This calculator provides decision support only.
 * The assessing nurse has full authority to override based on clinical judgment.</p>
 *
 * @see TriageAssessment
 * @see TriageLevel
 */
@Component
public class TriageCalculator {

    /**
     * Calculates the suggested triage level based on assessment data.
     *
     * <p>The algorithm evaluates in order of severity:
     * <ol>
     *   <li>Critical red flags (→ RED)</li>
     *   <li>Consciousness level (→ RED/ORANGE)</li>
     *   <li>Vital sign abnormalities</li>
     *   <li>Pain score</li>
     *   <li>Age-adjusted thresholds</li>
     * </ol>
     * </p>
     *
     * @param assessment the triage assessment with recorded vitals
     * @return suggested TriageLevel
     */
    public TriageLevel calculate(TriageAssessment assessment) {
        // Start with lowest priority, escalate based on findings
        TriageLevel level = TriageLevel.BLUE;

        // ==================== RED: IMMEDIATE ====================

        // Unresponsive patient
        if (assessment.getConsciousnessLevel() == TriageAssessment.ConsciousnessLevel.UNRESPONSIVE) {
            return TriageLevel.RED;
        }

        // Critical oxygen saturation
        if (assessment.getOxygenSaturation() != null && assessment.getOxygenSaturation() < 90) {
            return TriageLevel.RED;
        }

        // Stroke symptoms (FAST positive) - time critical
        if (assessment.isStrokeSymptoms()) {
            return TriageLevel.RED;
        }

        // Severe allergic reaction / anaphylaxis
        if (assessment.isAllergicReaction() && assessment.isDifficultyBreathing()) {
            return TriageLevel.RED;
        }

        // ==================== ORANGE: VERY URGENT ====================

        // Responds only to pain
        if (assessment.getConsciousnessLevel() == TriageAssessment.ConsciousnessLevel.PAIN) {
            return TriageLevel.ORANGE;
        }

        // Altered mental status
        if (assessment.isAlteredMentalStatus()) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Chest pain
        if (assessment.isChestPain()) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Severe bleeding
        if (assessment.isSeverebleeding()) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Difficulty breathing without critical SpO2
        if (assessment.isDifficultyBreathing()) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Low oxygen saturation (90-94%)
        if (assessment.getOxygenSaturation() != null &&
                assessment.getOxygenSaturation() >= 90 &&
                assessment.getOxygenSaturation() < 95) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Very high fever (≥40°C)
        if (assessment.getTemperatureCelsius() != null &&
                assessment.getTemperatureCelsius().compareTo(BigDecimal.valueOf(40.0)) >= 0) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Severe hypotension (SBP < 90)
        if (assessment.getBloodPressureSystolic() != null &&
                assessment.getBloodPressureSystolic() < 90) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Hypertensive crisis (SBP ≥ 180 or DBP ≥ 120)
        if ((assessment.getBloodPressureSystolic() != null && assessment.getBloodPressureSystolic() >= 180) ||
                (assessment.getBloodPressureDiastolic() != null && assessment.getBloodPressureDiastolic() >= 120)) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Severe pain (8-10)
        if (assessment.getPainScore() != null && assessment.getPainScore() >= 8) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // Pregnancy concern with red flags
        if (assessment.isPregnancyConcern()) {
            level = escalate(level, TriageLevel.ORANGE);
        }

        // ==================== YELLOW: URGENT ====================

        // Responds only to voice
        if (assessment.getConsciousnessLevel() == TriageAssessment.ConsciousnessLevel.VOICE) {
            level = escalate(level, TriageLevel.YELLOW);
        }

        // Severe abdominal pain
        if (assessment.isSevereAbdominalPain()) {
            level = escalate(level, TriageLevel.YELLOW);
        }

        // High fever (38.5-40°C)
        if (assessment.getTemperatureCelsius() != null) {
            BigDecimal temp = assessment.getTemperatureCelsius();
            if (temp.compareTo(BigDecimal.valueOf(38.5)) >= 0 &&
                    temp.compareTo(BigDecimal.valueOf(40.0)) < 0) {
                level = escalate(level, TriageLevel.YELLOW);
            }
        }

        // Moderate-severe pain (4-7)
        if (assessment.getPainScore() != null &&
                assessment.getPainScore() >= 4 &&
                assessment.getPainScore() < 8) {
            level = escalate(level, TriageLevel.YELLOW);
        }

        // Tachycardia (HR > 120) or bradycardia (HR < 50) in adults
        if (assessment.getHeartRateBpm() != null && !assessment.isPediatric()) {
            if (assessment.getHeartRateBpm() > 120 || assessment.getHeartRateBpm() < 50) {
                level = escalate(level, TriageLevel.YELLOW);
            }
        }

        // High blood pressure (SBP 160-179 or DBP 100-119)
        if (assessment.getBloodPressureSystolic() != null &&
                assessment.getBloodPressureSystolic() >= 160 &&
                assessment.getBloodPressureSystolic() < 180) {
            level = escalate(level, TriageLevel.YELLOW);
        }

        // Tachypnea (RR > 24)
        if (assessment.getRespiratoryRate() != null && assessment.getRespiratoryRate() > 24) {
            level = escalate(level, TriageLevel.YELLOW);
        }

        // ==================== GREEN: STANDARD ====================

        // Low-grade fever (37.5-38.5°C)
        if (assessment.getTemperatureCelsius() != null) {
            BigDecimal temp = assessment.getTemperatureCelsius();
            if (temp.compareTo(BigDecimal.valueOf(37.5)) >= 0 &&
                    temp.compareTo(BigDecimal.valueOf(38.5)) < 0) {
                level = escalate(level, TriageLevel.GREEN);
            }
        }

        // Mild pain (1-3)
        if (assessment.getPainScore() != null &&
                assessment.getPainScore() >= 1 &&
                assessment.getPainScore() < 4) {
            level = escalate(level, TriageLevel.GREEN);
        }

        // ==================== AGE ADJUSTMENTS ====================

        // Pediatric patients (especially infants) get escalated
        if (assessment.getPatientAgeYears() < 1) {
            // Infants under 1 year - escalate if not already urgent
            if (level == TriageLevel.BLUE || level == TriageLevel.GREEN) {
                level = TriageLevel.YELLOW;
            }
        } else if (assessment.getPatientAgeYears() < 5) {
            // Young children - escalate fever
            if (assessment.getTemperatureCelsius() != null &&
                    assessment.getTemperatureCelsius().compareTo(BigDecimal.valueOf(38.5)) >= 0) {
                level = escalate(level, TriageLevel.YELLOW);
            }
        }

        // Elderly patients with vital abnormalities get escalated
        if (assessment.isElderly()) {
            // Elderly may not mount fever - lower threshold
            if (assessment.getTemperatureCelsius() != null &&
                    assessment.getTemperatureCelsius().compareTo(BigDecimal.valueOf(38.0)) >= 0) {
                level = escalate(level, TriageLevel.YELLOW);
            }
        }

        return level;
    }

    /**
     * Escalates to the more severe triage level.
     *
     * @param current  current triage level
     * @param proposed proposed triage level
     * @return the more severe level
     */
    private TriageLevel escalate(TriageLevel current, TriageLevel proposed) {
        // Lower priority number = more severe
        if (proposed.getPriority() < current.getPriority()) {
            return proposed;
        }
        return current;
    }

    /**
     * Generates a summary of clinical findings that influenced the triage calculation.
     *
     * @param assessment the triage assessment
     * @return human-readable summary of findings
     */
    public String generateTriageSummary(TriageAssessment assessment) {
        StringBuilder summary = new StringBuilder();

        if (assessment.hasRedFlags()) {
            summary.append("RED FLAGS: ");
            if (assessment.isChestPain()) summary.append("Chest pain, ");
            if (assessment.isDifficultyBreathing()) summary.append("Difficulty breathing, ");
            if (assessment.isStrokeSymptoms()) summary.append("Stroke symptoms, ");
            if (assessment.isSeverebleeding()) summary.append("Severe bleeding, ");
            if (assessment.isAllergicReaction()) summary.append("Allergic reaction, ");
            if (assessment.isAlteredMentalStatus()) summary.append("Altered mental status, ");
            if (assessment.isPregnancyConcern()) summary.append("Pregnancy concern, ");
            if (assessment.isSevereAbdominalPain()) summary.append("Severe abdominal pain, ");
            summary.append("\n");
        }

        if (assessment.getOxygenSaturation() != null && assessment.getOxygenSaturation() < 95) {
            summary.append("Low SpO2: ").append(assessment.getOxygenSaturation()).append("%. ");
        }

        if (assessment.getTemperatureCelsius() != null) {
            BigDecimal temp = assessment.getTemperatureCelsius();
            if (temp.compareTo(BigDecimal.valueOf(38.0)) >= 0) {
                summary.append("Fever: ").append(temp).append("°C. ");
            }
        }

        if (assessment.getPainScore() != null && assessment.getPainScore() >= 4) {
            summary.append("Pain: ").append(assessment.getPainScore()).append("/10. ");
        }

        if (assessment.isPediatric()) {
            summary.append("Pediatric patient (").append(assessment.getPatientAgeYears()).append("y). ");
        }

        if (assessment.isElderly()) {
            summary.append("Elderly patient (").append(assessment.getPatientAgeYears()).append("y). ");
        }

        return summary.toString().trim();
    }
}
