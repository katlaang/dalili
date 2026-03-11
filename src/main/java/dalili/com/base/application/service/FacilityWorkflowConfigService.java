package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.facility.model.FacilityWorkflowConfig;
import dalili.com.base.domain.facility.repository.FacilityWorkflowConfigRepository;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central service for facility workflow feature flags.
 */
@Service
public class FacilityWorkflowConfigService {

    private static final Logger log = LoggerFactory.getLogger(FacilityWorkflowConfigService.class);

    private final FacilityWorkflowConfigRepository configRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;
    private final String defaultFacilityCode;
    private final String defaultFacilityName;

    public FacilityWorkflowConfigService(
            FacilityWorkflowConfigRepository configRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext,
            @Value("${dalili.clinic.code:DALILI_MAIN}") String defaultFacilityCode,
            @Value("${dalili.clinic.name:Dalili Health Clinic}") String defaultFacilityName
    ) {
        this.configRepository = configRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
        this.defaultFacilityCode = defaultFacilityCode;
        this.defaultFacilityName = defaultFacilityName;
    }

    @Transactional
    public FacilityWorkflowConfig getCurrentConfig() {
        return configRepository.findByFacilityCode(defaultFacilityCode)
                .orElseGet(() -> configRepository.save(FacilityWorkflowConfig.createDefault(
                        defaultFacilityCode,
                        defaultFacilityName,
                        "SYSTEM"
                )));
    }

    @Transactional
    public FacilityWorkflowConfig updateCurrentConfig(UpdateRequest request) {
        auditGuard.assertSessionActive();
        assertSuperAdmin();
        if (request == null) {
            throw new IllegalArgumentException("Configuration payload is required");
        }

        String actor = sessionContext.username() != null ? sessionContext.username() : "UNKNOWN";
        FacilityWorkflowConfig config = getCurrentConfig();
        config.applyUpdate(new FacilityWorkflowConfig.UpdateCommand(
                request.emergencyFlowEnabled(),
                request.appointmentFlowEnabled(),
                request.kioskEnabled(),
                request.qrCheckInEnabled(),
                request.patientPortalEnabled(),
                request.prescriptionRenewalEnabled(),
                request.aiTranscriptionEnabled(),
                request.aiDifferentialEnabled(),
                request.crossFacilityDataEnabled(),
                request.referralPrintingEnabled(),
                request.appointmentCheckInWindowMinutes(),
                request.appointmentPriorityBoostEnabled(),
                request.appointmentPriorityBoostMinutesBefore(),
                request.appointmentPriorityBoostMinutesAfter(),
                request.consentValidityHours(),
                request.requireTriageForAppointments(),
                actor
        ));

        config = configRepository.save(config);
        log.info("Facility workflow config updated facilityCode={} updatedBy={}",
                config.getFacilityCode(), actor);

        auditService.record(
                "FACILITY_WORKFLOW_CONFIG_UPDATED",
                null,
                "facilityCode=" + config.getFacilityCode() + ", updatedBy=" + actor
        );

        return config;
    }

    public boolean isEmergencyFlowEnabled() {
        return getCurrentConfig().isEmergencyFlowEnabled();
    }

    public boolean isAppointmentFlowEnabled() {
        return getCurrentConfig().isAppointmentFlowEnabled();
    }

    public boolean isKioskEnabled() {
        return getCurrentConfig().isKioskEnabled();
    }

    public boolean isPatientPortalEnabled() {
        return getCurrentConfig().isPatientPortalEnabled();
    }

    public int getAppointmentCheckInWindowMinutes() {
        return getCurrentConfig().getAppointmentCheckInWindowMinutes();
    }

    public int getAppointmentPriorityBoostMinutesBefore() {
        return getCurrentConfig().getAppointmentPriorityBoostMinutesBefore();
    }

    public int getAppointmentPriorityBoostMinutesAfter() {
        return getCurrentConfig().getAppointmentPriorityBoostMinutesAfter();
    }

    public boolean isAppointmentPriorityBoostEnabled() {
        return getCurrentConfig().isAppointmentPriorityBoostEnabled();
    }

    public void assertEmergencyFlowEnabled() {
        if (!isEmergencyFlowEnabled()) {
            throw new IllegalStateException("Emergency flow is disabled for this facility");
        }
    }

    public void assertAppointmentFlowEnabled() {
        if (!isAppointmentFlowEnabled()) {
            throw new IllegalStateException("Appointment flow is disabled for this facility");
        }
    }

    public void assertKioskEnabled() {
        if (!isKioskEnabled()) {
            throw new IllegalStateException("Kiosk check-in is disabled for this facility");
        }
    }

    public void assertPatientPortalEnabled() {
        if (!isPatientPortalEnabled()) {
            throw new IllegalStateException("Patient portal is disabled for this facility");
        }
    }

    private void assertSuperAdmin() {
        if (sessionContext.role() != Role.SUPER_ADMIN) {
            throw new IllegalStateException("Only super admin users can update facility workflow configuration");
        }
    }

    public record UpdateRequest(
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
            boolean requireTriageForAppointments
    ) {
    }
}
