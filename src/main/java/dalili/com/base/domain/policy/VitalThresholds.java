package dalili.com.base.domain.policy;

import java.util.List;

/**
 * VitalThresholds contains age-stratified threshold definitions.
 * <p>
 * Pediatric thresholds are split into 4 age bands for clinical accuracy:
 * - infant: < 1 year
 * - pediatric_1_2: 1-2 years
 * - pediatric_3_5: 3-5 years
 * - pediatric_6_10: 6-10 years
 * - pediatric_11_13: 11-13 years
 * - adult: >= 14 years
 */
public record VitalThresholds(
        AgeGroupThresholds adult,
        AgeGroupThresholds pediatric_1_2,
        AgeGroupThresholds pediatric_3_5,
        AgeGroupThresholds pediatric_6_10,
        AgeGroupThresholds pediatric_11_13,
        AgeGroupThresholds infant
) {

    /**
     * Get age-appropriate thresholds.
     */
    public AgeGroupThresholds getForAge(int ageYears) {
        if (ageYears < 1) {
            return infant;
        } else if (ageYears <= 2) {
            return pediatric_1_2 != null ? pediatric_1_2 : infant;
        } else if (ageYears <= 5) {
            return pediatric_3_5 != null ? pediatric_3_5 : pediatric_1_2;
        } else if (ageYears <= 10) {
            return pediatric_6_10 != null ? pediatric_6_10 : pediatric_3_5;
        } else if (ageYears <= 13) {
            return pediatric_11_13 != null ? pediatric_11_13 : pediatric_6_10;
        } else {
            return adult;
        }
    }
}

record ThresholdTier(
        Integer systolicBpBelow,
        Integer systolicBpAbove,
        Integer diastolicBpAbove,
        Integer oxygenSaturationBelow,
        Integer heartRateAbove,
        Integer heartRateBelow,
        Double temperatureAbove,
        Double temperatureBelow,
        Integer respiratoryRateAbove,
        Integer respiratoryRateBelow,
        Integer gcsBelow
) {

    public boolean matches(VitalSigns vitals) {
        return !getMatchedThresholds(vitals).isEmpty();
    }

    public List<ThresholdViolation> getMatchedThresholds(VitalSigns vitals) {
        var violations = new java.util.ArrayList<ThresholdViolation>();

        if (systolicBpBelow != null && vitals.systolicBp() != null
                && vitals.systolicBp() < systolicBpBelow) {
            violations.add(new ThresholdViolation("systolicBp", "below",
                    systolicBpBelow.doubleValue(), vitals.systolicBp().doubleValue()));
        }
        if (systolicBpAbove != null && vitals.systolicBp() != null
                && vitals.systolicBp() > systolicBpAbove) {
            violations.add(new ThresholdViolation("systolicBp", "above",
                    systolicBpAbove.doubleValue(), vitals.systolicBp().doubleValue()));
        }
        if (diastolicBpAbove != null && vitals.diastolicBp() != null
                && vitals.diastolicBp() > diastolicBpAbove) {
            violations.add(new ThresholdViolation("diastolicBp", "above",
                    diastolicBpAbove.doubleValue(), vitals.diastolicBp().doubleValue()));
        }
        if (oxygenSaturationBelow != null && vitals.oxygenSaturation() != null
                && vitals.oxygenSaturation() < oxygenSaturationBelow) {
            violations.add(new ThresholdViolation("oxygenSaturation", "below",
                    oxygenSaturationBelow.doubleValue(), vitals.oxygenSaturation().doubleValue()));
        }
        if (heartRateAbove != null && vitals.heartRate() != null
                && vitals.heartRate() > heartRateAbove) {
            violations.add(new ThresholdViolation("heartRate", "above",
                    heartRateAbove.doubleValue(), vitals.heartRate().doubleValue()));
        }
        if (heartRateBelow != null && vitals.heartRate() != null
                && vitals.heartRate() < heartRateBelow) {
            violations.add(new ThresholdViolation("heartRate", "below",
                    heartRateBelow.doubleValue(), vitals.heartRate().doubleValue()));
        }
        if (temperatureAbove != null && vitals.temperature() != null
                && vitals.temperature() > temperatureAbove) {
            violations.add(new ThresholdViolation("temperature", "above",
                    temperatureAbove, vitals.temperature()));
        }
        if (temperatureBelow != null && vitals.temperature() != null
                && vitals.temperature() < temperatureBelow) {
            violations.add(new ThresholdViolation("temperature", "below",
                    temperatureBelow, vitals.temperature()));
        }
        if (respiratoryRateAbove != null && vitals.respiratoryRate() != null
                && vitals.respiratoryRate() > respiratoryRateAbove) {
            violations.add(new ThresholdViolation("respiratoryRate", "above",
                    respiratoryRateAbove.doubleValue(), vitals.respiratoryRate().doubleValue()));
        }
        if (respiratoryRateBelow != null && vitals.respiratoryRate() != null
                && vitals.respiratoryRate() < respiratoryRateBelow) {
            violations.add(new ThresholdViolation("respiratoryRate", "below",
                    respiratoryRateBelow.doubleValue(), vitals.respiratoryRate().doubleValue()));
        }
        if (gcsBelow != null && vitals.gcs() != null && vitals.gcs() < gcsBelow) {
            violations.add(new ThresholdViolation("gcs", "below",
                    gcsBelow.doubleValue(), vitals.gcs().doubleValue()));
        }
        
        return violations;
    }
}
