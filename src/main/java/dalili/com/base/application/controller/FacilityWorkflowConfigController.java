package dalili.com.base.application.controller;

import dalili.com.base.application.service.FacilityWorkflowConfigService;
import dalili.com.base.domain.facility.model.FacilityWorkflowConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Facility-level workflow configuration endpoints.
 */
@RestController
@RequestMapping("/api/facility/workflow-config")
public class FacilityWorkflowConfigController {

    private static final Logger log = LoggerFactory.getLogger(FacilityWorkflowConfigController.class);

    private final FacilityWorkflowConfigService configService;

    public FacilityWorkflowConfigController(FacilityWorkflowConfigService configService) {
        this.configService = configService;
    }

    private static boolean bool(Boolean value, boolean defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static int intOr(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    @GetMapping
    public ResponseEntity<?> getCurrentConfig() {
        try {
            log.debug("Facility workflow config requested");
            return ResponseEntity.ok(Response.from(configService.getCurrentConfig()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping
    public ResponseEntity<?> updateConfig(@RequestBody UpdateRequest request) {
        try {
            FacilityWorkflowConfig config = configService.updateCurrentConfig(new FacilityWorkflowConfigService.UpdateRequest(
                    bool(request.emergencyFlowEnabled(), true),
                    bool(request.appointmentFlowEnabled(), true),
                    bool(request.kioskEnabled(), true),
                    bool(request.qrCheckInEnabled(), true),
                    bool(request.patientPortalEnabled(), true),
                    bool(request.prescriptionRenewalEnabled(), true),
                    bool(request.aiTranscriptionEnabled(), true),
                    bool(request.aiDifferentialEnabled(), true),
                    bool(request.crossFacilityDataEnabled(), true),
                    bool(request.referralPrintingEnabled(), true),
                    intOr(request.appointmentCheckInWindowMinutes(), 30),
                    bool(request.appointmentPriorityBoostEnabled(), true),
                    intOr(request.appointmentPriorityBoostMinutesBefore(), 5),
                    intOr(request.appointmentPriorityBoostMinutesAfter(), 15),
                    intOr(request.consentValidityHours(), 24),
                    bool(request.requireTriageForAppointments(), true)
            ));
            log.info("Facility workflow config updated facilityCode={}", config.getFacilityCode());

            return ResponseEntity.ok(Response.from(config));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    public record UpdateRequest(
            Boolean emergencyFlowEnabled,
            Boolean appointmentFlowEnabled,
            Boolean kioskEnabled,
            Boolean qrCheckInEnabled,
            Boolean patientPortalEnabled,
            Boolean prescriptionRenewalEnabled,
            Boolean aiTranscriptionEnabled,
            Boolean aiDifferentialEnabled,
            Boolean crossFacilityDataEnabled,
            Boolean referralPrintingEnabled,
            Integer appointmentCheckInWindowMinutes,
            Boolean appointmentPriorityBoostEnabled,
            Integer appointmentPriorityBoostMinutesBefore,
            Integer appointmentPriorityBoostMinutesAfter,
            Integer consentValidityHours,
            Boolean requireTriageForAppointments
    ) {
    }

    public record ErrorResponse(String error) {
    }

    public record Response(
            String facilityCode,
            String facilityName,
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
            String updatedAt,
            String updatedBy
    ) {
        static Response from(FacilityWorkflowConfig config) {
            return new Response(
                    config.getFacilityCode(),
                    config.getFacilityName(),
                    config.isEmergencyFlowEnabled(),
                    config.isAppointmentFlowEnabled(),
                    config.isKioskEnabled(),
                    config.isQrCheckInEnabled(),
                    config.isPatientPortalEnabled(),
                    config.isPrescriptionRenewalEnabled(),
                    config.isAiTranscriptionEnabled(),
                    config.isAiDifferentialEnabled(),
                    config.isCrossFacilityDataEnabled(),
                    config.isReferralPrintingEnabled(),
                    config.getAppointmentCheckInWindowMinutes(),
                    config.isAppointmentPriorityBoostEnabled(),
                    config.getAppointmentPriorityBoostMinutesBefore(),
                    config.getAppointmentPriorityBoostMinutesAfter(),
                    config.getConsentValidityHours(),
                    config.isRequireTriageForAppointments(),
                    config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null,
                    config.getUpdatedBy()
            );
        }
    }
}
