package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.PatientDataAuthorization;
import dalili.com.base.domain.patient.model.PatientDataAuthorization.DataAccessScope;
import dalili.com.base.domain.patient.repository.PatientDataAuthorizationRepository;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Centralized patient data authorization and scope resolution.
 */
@Service
public class PatientDataAccessService {

    private static final Logger log = LoggerFactory.getLogger(PatientDataAccessService.class);

    private final PatientDataAuthorizationRepository authorizationRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final PatientService patientService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;
    private final String facilityCode;

    public PatientDataAccessService(
            PatientDataAuthorizationRepository authorizationRepository,
            QueueTicketRepository queueTicketRepository,
            PatientService patientService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext,
            @Value("${dalili.clinic.code:DALILI_MAIN}") String facilityCode
    ) {
        this.authorizationRepository = authorizationRepository;
        this.queueTicketRepository = queueTicketRepository;
        this.patientService = patientService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
        this.facilityCode = facilityCode;
    }

    @Transactional
    public PatientDataAuthorization recordPatientConsent(UUID patientId) {
        auditGuard.assertSessionActive();
        log.info("Recording patient consent authorization patientId={}", patientId);
        patientService.recordConsent(patientId);

        String authorizerId = sessionContext.username();
        String authorizerName = sessionContext.username();
        PatientDataAuthorization authorization = PatientDataAuthorization.grantPatientConsent(
                patientId,
                facilityCode,
                authorizerId,
                authorizerName != null ? authorizerName : "Patient"
        );

        authorization = authorizationRepository.save(authorization);

        auditService.record(
                "PATIENT_DATA_AUTH_GRANTED",
                patientId,
                "Access type=PATIENT_CONSENT scope=FULL_ACCESS facility=" + facilityCode
        );
        log.info("Patient consent authorization granted patientId={} authorizationId={}", patientId, authorization.getId());
        return authorization;
    }

    @Transactional
    public PatientDataAuthorization recordNextOfKinApproval(UUID patientId, NextOfKinApprovalInput input) {
        auditGuard.assertSessionActive();
        if (input == null) {
            throw new AccessException("Next of kin approval details are required");
        }
        log.info("Recording next-of-kin authorization patientId={}", patientId);

        PatientDataAuthorization authorization = PatientDataAuthorization.grantNextOfKinApproval(
                patientId,
                facilityCode,
                sessionContext.username(),
                input.approverName(),
                input.nextOfKinName(),
                input.nextOfKinPhone(),
                input.relationship(),
                input.verificationMethod(),
                input.expiresAt() != null ? input.expiresAt() : Instant.now().plus(Duration.ofHours(24))
        );
        authorization = authorizationRepository.save(authorization);

        auditService.record(
                "PATIENT_DATA_AUTH_GRANTED",
                patientId,
                "Access type=NEXT_OF_KIN_APPROVAL scope=FULL_ACCESS facility=" + facilityCode
        );
        log.info("Next-of-kin authorization granted patientId={} authorizationId={}", patientId, authorization.getId());
        return authorization;
    }

    @Transactional
    public PatientDataAuthorization recordEmergencyBreakGlass(UUID patientId, EmergencyBreakGlassInput input) {
        auditGuard.assertSessionActive();
        assertClinicianRole();

        if (input == null || input.justification() == null || input.justification().isBlank()) {
            throw new AccessException("Emergency justification is required");
        }
        log.info("Recording emergency break-glass authorization patientId={} role={}", patientId, sessionContext.role());

        QueueTicket emergencyTicket = findActiveEmergencyTicket(patientId)
                .orElseThrow(() -> new AccessException("Emergency break-glass allowed only with active emergency ticket"));

        PatientDataAuthorization authorization = PatientDataAuthorization.grantEmergencyBreakGlass(
                patientId,
                facilityCode,
                sessionContext.username(),
                sessionContext.username(),
                sessionContext.role() != null ? sessionContext.role().name() : "CLINICIAN",
                input.justification(),
                emergencyTicket.getTriageAssessmentId()
        );
        authorization = authorizationRepository.save(authorization);

        auditService.record(
                "PATIENT_DATA_AUTH_GRANTED",
                patientId,
                "Access type=EMERGENCY_BREAK_GLASS scope=EMERGENCY_ONLY triage=" + emergencyTicket.getTriageLevel()
        );
        log.info("Emergency break-glass authorization granted patientId={} authorizationId={} triageLevel={}",
                patientId, authorization.getId(), emergencyTicket.getTriageLevel());
        return authorization;
    }

    @Transactional
    public PatientDataAuthorization recordAdmissionGrant(UUID patientId, UUID admissionTicketId) {
        auditGuard.assertSessionActive();
        log.info("Recording admission-based authorization patientId={} admissionTicketId={}", patientId, admissionTicketId);
        PatientDataAuthorization authorization = PatientDataAuthorization.grantAdmissionAccess(
                patientId,
                facilityCode,
                sessionContext.username(),
                sessionContext.username(),
                admissionTicketId
        );
        authorization = authorizationRepository.save(authorization);

        auditService.record(
                "PATIENT_DATA_AUTH_GRANTED",
                patientId,
                "Access type=ADMISSION_GRANT scope=FULL_ACCESS ticket=" + admissionTicketId
        );
        log.info("Admission authorization granted patientId={} authorizationId={}", patientId, authorization.getId());
        return authorization;
    }

    public AccessResolution resolveAccess(UUID patientId) {
        if (isCurrentlyAdmittedSameHospital(patientId)) {
            AccessResolution resolution = new AccessResolution(DataAccessScope.FULL_ACCESS, "Active same-hospital admission", true);
            log.info("Access resolved patientId={} scope={} admittedOverride={}",
                    patientId, resolution.scope(), resolution.admittedOverride());
            return resolution;
        }

        List<PatientDataAuthorization> authorizations = authorizationRepository.findActiveByPatientAndFacility(
                patientId,
                facilityCode,
                Instant.now()
        );

        boolean hasFull = authorizations.stream()
                .anyMatch(a -> a.getDataAccessScope() == DataAccessScope.FULL_ACCESS);
        if (hasFull) {
            AccessResolution resolution = new AccessResolution(DataAccessScope.FULL_ACCESS, "Consent/approval available", false);
            log.info("Access resolved patientId={} scope={} admittedOverride={}",
                    patientId, resolution.scope(), resolution.admittedOverride());
            return resolution;
        }

        boolean hasEmergencyOnly = authorizations.stream()
                .anyMatch(a -> a.getDataAccessScope() == DataAccessScope.EMERGENCY_ONLY);
        if (hasEmergencyOnly) {
            AccessResolution resolution = new AccessResolution(DataAccessScope.EMERGENCY_ONLY, "Emergency break-glass authorization", false);
            log.info("Access resolved patientId={} scope={} admittedOverride={}",
                    patientId, resolution.scope(), resolution.admittedOverride());
            return resolution;
        }

        AccessResolution resolution = new AccessResolution(DataAccessScope.NONE, "No active data authorization", false);
        log.info("Access resolved patientId={} scope={} admittedOverride={}",
                patientId, resolution.scope(), resolution.admittedOverride());
        return resolution;
    }

    public List<PatientDataAuthorization> getAuthorizationHistory(UUID patientId) {
        auditGuard.assertSessionActive();
        List<PatientDataAuthorization> authorizations =
                authorizationRepository.findByPatientIdAndFacilityCodeOrderByGrantedAtDesc(patientId, facilityCode);
        auditService.record("PATIENT_DATA_AUTH_HISTORY_VIEWED", patientId,
                "Authorization history viewed count=" + authorizations.size());
        log.info("Authorization history fetched patientId={} count={}", patientId, authorizations.size());
        return authorizations;
    }

    public boolean hasActiveEmergencyTicket(UUID patientId) {
        return findActiveEmergencyTicket(patientId).isPresent();
    }

    private java.util.Optional<QueueTicket> findActiveEmergencyTicket(UUID patientId) {
        LocalDate today = LocalDate.now();
        List<QueueTicket.QueueStatus> activeStatuses = List.of(
                QueueTicket.QueueStatus.WAITING,
                QueueTicket.QueueStatus.CALLED,
                QueueTicket.QueueStatus.IN_PROGRESS
        );
        return queueTicketRepository.findByPatientIdAndQueueDateAndStatusInAndCategory(
                        patientId,
                        today,
                        activeStatuses,
                        QueueTicket.QueueCategory.EMERGENCY
                ).stream()
                .findFirst();
    }

    private boolean isCurrentlyAdmittedSameHospital(UUID patientId) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(72));
        return queueTicketRepository.findByPatientIdOrderByQueueDateDescCreatedAtDesc(patientId)
                .stream()
                .filter(ticket -> ticket.getStatus() == QueueTicket.QueueStatus.ADMITTED)
                .anyMatch(ticket -> ticket.getCompletedAt() != null && ticket.getCompletedAt().isAfter(cutoff));
    }

    private void assertClinicianRole() {
        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.NURSE && role != Role.ADMIN) {
            throw new AccessException("Only clinical staff can initiate emergency break-glass access");
        }
    }

    public record AccessResolution(
            DataAccessScope scope,
            String reason,
            boolean admittedOverride
    ) {
    }

    public record EmergencyBreakGlassInput(String justification) {
    }

    public record NextOfKinApprovalInput(
            String approverName,
            String nextOfKinName,
            String nextOfKinPhone,
            String relationship,
            String verificationMethod,
            Instant expiresAt
    ) {
    }

    public static class AccessException extends RuntimeException {
        public AccessException(String message) {
            super(message);
        }
    }
}


