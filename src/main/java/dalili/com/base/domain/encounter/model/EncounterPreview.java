package dalili.com.base.domain.encounter.model;

import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.triage.TriageAssessment;
import dalili.com.base.domain.triage.TriageLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Comprehensive pre-encounter view for physicians.
 *
 * <p>This record aggregates all relevant patient information before
 * the physician begins the consultation, including:
 * <ul>
 *   <li>Patient demographics</li>
 *   <li>Queue and wait time information</li>
 *   <li>Triage assessment with vitals and red flags</li>
 *   <li>Historical medication list</li>
 *   <li>Alerts and warnings</li>
 * </ul>
 * </p>
 *
 * <p>This allows the physician to review the complete clinical picture
 * before calling the patient, enabling more efficient consultations.</p>
 */
public record EncounterPreview(
        // ==================== PATIENT INFO ====================
        PatientSummary patient,

        // ==================== QUEUE INFO ====================
        QueueSummary queue,

        // ==================== TRIAGE INFO ====================
        TriageSummary triage,

        // ==================== MEDICATION HISTORY ====================
        List<MedicationHistory> activeMedications,

        // ==================== ALERTS ====================
        List<ClinicalAlert> alerts,

        // ==================== PREVIOUS ENCOUNTERS ====================
        List<PreviousEncounterSummary> recentEncounters,

        // ==================== REPEAT CARE SUMMARY ====================
        RepeatCareSummary repeatCareSummary,

        // ==================== DIAGNOSIS HISTORY ====================
        List<HistoricalDiagnosis> diagnosisHistory,

        // ==================== VITAL TRENDS ====================
        List<VitalTrendPoint> vitalTrends,

        // ==================== CARE PLAN HISTORY ====================
        List<CarePlanHistory> carePlanHistory
) {
    /**
     * Alert severity levels.
     */
    public enum AlertSeverity {
        /**
         * Information only
         */
        INFO,
        /**
         * Requires attention but not critical
         */
        WARNING,
        /**
         * Critical - requires immediate attention
         */
        CRITICAL
    }

    /**
     * Types of clinical alerts.
     */
    public enum AlertType {
        /**
         * Vital sign abnormality
         */
        VITAL_ABNORMALITY,
        /**
         * Red flag symptom present
         */
        RED_FLAG,
        /**
         * Drug allergy
         */
        ALLERGY,
        /**
         * Overdue wait time
         */
        OVERDUE,
        /**
         * Condition deteriorating
         */
        DETERIORATING,
        /**
         * Known chronic condition
         */
        CHRONIC_CONDITION,
        /**
         * Medication interaction risk
         */
        DRUG_INTERACTION,
        /**
         * Patient is elderly
         */
        ELDERLY,
        /**
         * Patient is pediatric
         */
        PEDIATRIC,
        /**
         * Pregnancy related
         */
        PREGNANCY
    }

    /**
     * Patient demographic summary.
     */
    public record PatientSummary(
            UUID id,
            String mrn,
            String fullName,
            LocalDate dateOfBirth,
            int ageYears,
            Patient.Sex sex,
            String phoneNumber,
            String emergencyContactName,
            String emergencyContactPhone,
            boolean hasActiveAllergies
    ) {
        public static PatientSummary from(Patient p) {
            int age = java.time.Period.between(p.getDateOfBirth(), LocalDate.now()).getYears();
            return new PatientSummary(
                    p.getId(),
                    p.getMrn(),
                    p.getFullName(),
                    p.getDateOfBirth(),
                    age,
                    p.getSex(),
                    p.getPhoneNumber(),
                    p.getEmergencyContactName(),
                    p.getEmergencyContactPhone(),
                    false // Will be set by service
            );
        }
    }

    /**
     * Queue and wait time summary.
     */
    public record QueueSummary(
            UUID ticketId,
            String ticketNumber,
            QueueTicket.QueueCategory category,
            TriageLevel triageLevel,
            Instant checkedInAt,
            long waitTimeMinutes,
            int targetWaitMinutes,
            boolean isOverdue,
            int missedCallCount,
            boolean ambulanceArrival,
            String assignedClinicianName,
            String assignedClinicianEmployeeId,
            String clinicianAssignmentSource,
            Instant clinicianAssignedAt
    ) {
        public static QueueSummary from(QueueTicket t) {
            return new QueueSummary(
                    t.getId(),
                    t.getTicketNumber(),
                    t.getCategory(),
                    t.getTriageLevel(),
                    t.getCreatedAt(),
                    t.getWaitTimeMinutes(),
                    t.getTargetWaitMinutes(),
                    t.isOverdue(),
                    t.getMissedCallCount(),
                    t.isAmbulanceArrival(),
                    t.getAssignedClinicianName(),
                    t.getAssignedClinicianEmployeeId(),
                    t.getClinicianAssignmentSource(),
                    t.getClinicianAssignedAt()
            );
        }
    }

    /**
     * Triage assessment summary with vitals and red flags.
     */
    public record TriageSummary(
            UUID assessmentId,
            String chiefComplaint,

            // Vitals
            BigDecimal temperatureCelsius,
            Integer heartRateBpm,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer respiratoryRate,
            Integer oxygenSaturation,
            BigDecimal weightKg,
            BigDecimal heightCm,
            Integer painScore,
            BigDecimal bloodGlucoseMmol,
            TriageAssessment.ConsciousnessLevel consciousnessLevel,

            // Calculated
            BigDecimal bmi,
            Integer meanArterialPressure,

            // Red flags
            boolean hasRedFlags,
            boolean chestPain,
            boolean difficultyBreathing,
            boolean strokeSymptoms,
            boolean severebleeding,
            boolean allergicReaction,
            boolean alteredMentalStatus,
            boolean pregnancyConcern,
            boolean severeAbdominalPain,

            // Clinical observations
            String historyOfPresentIllness,
            String allergies,
            String currentMedications,
            String pastMedicalHistory,
            String nursingNotes,

            // Triage classification
            TriageLevel systemTriageLevel,
            TriageLevel finalTriageLevel,
            boolean triageOverridden,
            String overrideReason,

            // Metadata
            Instant assessedAt,
            String assessedByStaffName,
            boolean isReassessment
    ) {
        public static TriageSummary from(TriageAssessment a) {
            return new TriageSummary(
                    a.getId(),
                    a.getChiefComplaint(),
                    a.getTemperatureCelsius(),
                    a.getHeartRateBpm(),
                    a.getBloodPressureSystolic(),
                    a.getBloodPressureDiastolic(),
                    a.getRespiratoryRate(),
                    a.getOxygenSaturation(),
                    a.getWeightKg(),
                    a.getHeightCm(),
                    a.getPainScore(),
                    a.getBloodGlucoseMmol(),
                    a.getConsciousnessLevel(),
                    a.getBmi(),
                    a.getMeanArterialPressure(),
                    a.hasRedFlags(),
                    a.isChestPain(),
                    a.isDifficultyBreathing(),
                    a.isStrokeSymptoms(),
                    a.isSeverebleeding(),
                    a.isAllergicReaction(),
                    a.isAlteredMentalStatus(),
                    a.isPregnancyConcern(),
                    a.isSevereAbdominalPain(),
                    a.getHistoryOfPresentIllness(),
                    a.getAllergies(),
                    a.getCurrentMedications(),
                    a.getPastMedicalHistory(),
                    a.getNursingNotes(),
                    a.getSystemTriageLevel(),
                    a.getFinalTriageLevel(),
                    a.isTriageOverridden(),
                    a.getOverrideReason(),
                    a.getAssessedAt(),
                    a.getAssessedByStaffName(),
                    a.isReassessment()
            );
        }
    }

    /**
     * Active medication from patient history.
     */
    public record MedicationHistory(
            String medicationName,
            String dosage,
            String frequency,
            LocalDate startDate,
            String prescribedBy,
            boolean currentlyTaking,
            boolean needsReconciliation
    ) {
    }

    /**
     * Clinical alert for physician attention.
     */
    public record ClinicalAlert(
            AlertSeverity severity,
            AlertType type,
            String message,
            String details
    ) {
    }

    /**
     * Summary of previous encounter.
     */
    public record PreviousEncounterSummary(
            UUID encounterId,
            LocalDate date,
            String encounterType,
            String primaryDiagnosis,
            String clinicianName
    ) {
    }

    /**
     * Repeat-care relationship summary for current clinician.
     */
    public record RepeatCareSummary(
            boolean repeatPatientWithCurrentClinician,
            UUID currentClinicianId,
            String currentClinicianName,
            int visitsWithCurrentClinician,
            int totalCompletedVisits,
            LocalDate lastVisitWithCurrentClinicianAt
    ) {
    }

    /**
     * Historical diagnosis item for quick clinician review.
     */
    public record HistoricalDiagnosis(
            UUID encounterId,
            LocalDate date,
            String icdCode,
            String description,
            boolean primary,
            String clinicianName,
            boolean fromCurrentClinician
    ) {
    }

    /**
     * Historical vitals progression point (used for trend bars/charts).
     */
    public record VitalTrendPoint(
            UUID assessmentId,
            LocalDate date,
            Integer bloodPressureSystolic,
            Integer bloodPressureDiastolic,
            Integer heartRateBpm,
            Integer oxygenSaturation,
            BigDecimal temperatureCelsius,
            Integer painScore
    ) {
    }

    /**
     * Snapshot of prior care plan suggestions to expose suggested labs/treatment history.
     */
    public record CarePlanHistory(
            UUID encounterId,
            LocalDate date,
            String clinicianName,
            String suggestionSummary
    ) {
    }
}
