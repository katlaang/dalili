package dalili.com.base.domain.facility.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Facility-level workflow and feature toggle configuration.
 *
 * <p>The platform currently operates as a single-facility deployment per backend
 * instance, but the model keeps a facility code to support multiple facilities
 * under the same platform footprint.</p>
 */
@Entity
@Table(name = "facility_workflow_config", indexes = {
        @Index(name = "idx_facility_config_code", columnList = "facilityCode", unique = true)
})
@Getter
public class FacilityWorkflowConfig {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String facilityCode;

    @Column(nullable = false, length = 255)
    private String facilityName;

    @Column(nullable = false)
    private boolean emergencyFlowEnabled;

    @Column(nullable = false)
    private boolean appointmentFlowEnabled;

    @Column(nullable = false)
    private boolean kioskEnabled;

    @Column(nullable = false)
    private boolean qrCheckInEnabled;

    @Column(nullable = false)
    private boolean patientPortalEnabled;

    @Column(nullable = false)
    private boolean prescriptionRenewalEnabled;

    @Column(nullable = false)
    private boolean aiTranscriptionEnabled;

    @Column(nullable = false)
    private boolean aiDifferentialEnabled;

    @Column(nullable = false)
    private boolean crossFacilityDataEnabled;

    @Column(nullable = false)
    private boolean referralPrintingEnabled;

    @Column(nullable = false)
    private int appointmentCheckInWindowMinutes;

    @Column(nullable = false)
    private boolean appointmentPriorityBoostEnabled;

    @Column(nullable = false)
    private int appointmentPriorityBoostMinutesBefore;

    @Column(nullable = false)
    private int appointmentPriorityBoostMinutesAfter;

    @Column(nullable = false)
    private int consentValidityHours;

    @Column(nullable = false)
    private boolean requireTriageForAppointments;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, length = 120)
    private String updatedBy;

    protected FacilityWorkflowConfig() {
    }

    public static FacilityWorkflowConfig createDefault(String facilityCode, String facilityName, String updatedBy) {
        FacilityWorkflowConfig config = new FacilityWorkflowConfig();
        config.facilityCode = normalizeRequired(facilityCode, "facilityCode", 80);
        config.facilityName = normalizeRequired(facilityName, "facilityName", 255);
        config.emergencyFlowEnabled = true;
        config.appointmentFlowEnabled = true;
        config.kioskEnabled = true;
        config.qrCheckInEnabled = true;
        config.patientPortalEnabled = true;
        config.prescriptionRenewalEnabled = true;
        config.aiTranscriptionEnabled = true;
        config.aiDifferentialEnabled = true;
        config.crossFacilityDataEnabled = true;
        config.referralPrintingEnabled = true;
        config.appointmentCheckInWindowMinutes = 30;
        config.appointmentPriorityBoostEnabled = true;
        config.appointmentPriorityBoostMinutesBefore = 5;
        config.appointmentPriorityBoostMinutesAfter = 15;
        config.consentValidityHours = 24;
        config.requireTriageForAppointments = true;
        config.updatedAt = Instant.now();
        config.updatedBy = normalizeRequired(updatedBy, "updatedBy", 120);
        return config;
    }

    private static int ensureRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static String normalizeRequired(String value, String fieldName, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    public void applyUpdate(UpdateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Workflow configuration update is required");
        }

        this.emergencyFlowEnabled = command.emergencyFlowEnabled();
        this.appointmentFlowEnabled = command.appointmentFlowEnabled();
        this.kioskEnabled = command.kioskEnabled();
        this.qrCheckInEnabled = command.qrCheckInEnabled();
        this.patientPortalEnabled = command.patientPortalEnabled();
        this.prescriptionRenewalEnabled = command.prescriptionRenewalEnabled();
        this.aiTranscriptionEnabled = command.aiTranscriptionEnabled();
        this.aiDifferentialEnabled = command.aiDifferentialEnabled();
        this.crossFacilityDataEnabled = command.crossFacilityDataEnabled();
        this.referralPrintingEnabled = command.referralPrintingEnabled();
        this.appointmentCheckInWindowMinutes = ensureRange(
                command.appointmentCheckInWindowMinutes(), 5, 180, "appointmentCheckInWindowMinutes"
        );
        this.appointmentPriorityBoostEnabled = command.appointmentPriorityBoostEnabled();
        this.appointmentPriorityBoostMinutesBefore = ensureRange(
                command.appointmentPriorityBoostMinutesBefore(), 0, 60, "appointmentPriorityBoostMinutesBefore"
        );
        this.appointmentPriorityBoostMinutesAfter = ensureRange(
                command.appointmentPriorityBoostMinutesAfter(), 0, 120, "appointmentPriorityBoostMinutesAfter"
        );
        this.consentValidityHours = ensureRange(command.consentValidityHours(), 1, 240, "consentValidityHours");
        this.requireTriageForAppointments = command.requireTriageForAppointments();
        this.updatedBy = normalizeRequired(command.updatedBy(), "updatedBy", 120);
        this.updatedAt = Instant.now();
    }

    public record UpdateCommand(
            boolean emergencyFlowEnabled,
            boolean appointmentFlowEnabled,
            boolean kioskEnabled,
            boolean qrCheckInEnabled,
            boolean patientPortalEnabled,
            boolean prescriptionRenewalEnabled,
            boolean aiTranscriptionEnabled,
            boolean aiDifferentialEnabled,
            boolean crossFacilityDataEnabled,
            boolean referralPrintingEnabled,
            int appointmentCheckInWindowMinutes,
            boolean appointmentPriorityBoostEnabled,
            int appointmentPriorityBoostMinutesBefore,
            int appointmentPriorityBoostMinutesAfter,
            int consentValidityHours,
            boolean requireTriageForAppointments,
            String updatedBy
    ) {
    }
}
