package dalili.com.base.application.controller;

import dalili.com.base.application.service.*;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.encounter.repository.MedicationOrderRepository;
import dalili.com.base.domain.patient.model.PatientDataTransferRequest;
import dalili.com.base.domain.patient.model.PortalMessage;
import dalili.com.base.domain.patient.model.PrescriptionRenewalRequest;
import dalili.com.base.domain.patient.model.ReferralRecord;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clinician endpoints for consented/emergency patient-data access and portal operations.
 */
@RestController
@RequestMapping("/api/clinical/patient-data")
public class ClinicalPatientDataController {

    private static final Logger log = LoggerFactory.getLogger(ClinicalPatientDataController.class);

    private final AuditGuard auditGuard;
    private final AuditService auditService;
    private final PatientService patientService;
    private final PatientDataAccessService patientDataAccessService;
    private final PatientDataTransferService patientDataTransferService;
    private final PatientEmergencyDataService patientEmergencyDataService;
    private final PrescriptionRenewalService prescriptionRenewalService;
    private final PortalMessagingService portalMessagingService;
    private final PatientClinicalRecordsService clinicalRecordsService;
    private final ReferralPrintService referralPrintService;
    private final EncounterRepository encounterRepository;
    private final MedicationOrderRepository medicationOrderRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final MedicationSafetyService medicationSafetyService;

    public ClinicalPatientDataController(
            AuditGuard auditGuard,
            AuditService auditService,
            PatientService patientService,
            PatientDataAccessService patientDataAccessService,
            PatientDataTransferService patientDataTransferService,
            PatientEmergencyDataService patientEmergencyDataService,
            PrescriptionRenewalService prescriptionRenewalService,
            PortalMessagingService portalMessagingService,
            PatientClinicalRecordsService clinicalRecordsService,
            ReferralPrintService referralPrintService,
            EncounterRepository encounterRepository,
            MedicationOrderRepository medicationOrderRepository,
            QueueTicketRepository queueTicketRepository,
            MedicationSafetyService medicationSafetyService
    ) {
        this.auditGuard = auditGuard;
        this.auditService = auditService;
        this.patientService = patientService;
        this.patientDataAccessService = patientDataAccessService;
        this.patientDataTransferService = patientDataTransferService;
        this.patientEmergencyDataService = patientEmergencyDataService;
        this.prescriptionRenewalService = prescriptionRenewalService;
        this.portalMessagingService = portalMessagingService;
        this.clinicalRecordsService = clinicalRecordsService;
        this.referralPrintService = referralPrintService;
        this.encounterRepository = encounterRepository;
        this.medicationOrderRepository = medicationOrderRepository;
        this.queueTicketRepository = queueTicketRepository;
        this.medicationSafetyService = medicationSafetyService;
    }

    @PostMapping("/{patientId}/break-glass")
    public ResponseEntity<?> breakGlass(
            @PathVariable UUID patientId,
            @RequestBody EmergencyBreakGlassRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            log.info("Emergency break-glass request patientId={}", patientId);
            var authorization = patientDataAccessService.recordEmergencyBreakGlass(
                    patientId,
                    new PatientDataAccessService.EmergencyBreakGlassInput(request.justification())
            );
            auditService.record("EMERGENCY_BREAK_GLASS_GRANTED", patientId, "Break-glass authorization granted");
            log.info("Emergency break-glass granted patientId={} authorizationId={}", patientId, authorization.getId());
            return ResponseEntity.ok(PatientPortalController.AuthorizationView.from(authorization));
        } catch (RuntimeException e) {
            log.warn("Emergency break-glass failed patientId={} reason={}", patientId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{patientId}/next-of-kin-approval")
    public ResponseEntity<?> nextOfKinApproval(
            @PathVariable UUID patientId,
            @RequestBody NextOfKinApprovalRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            log.info("Next-of-kin approval request patientId={} relationship={}", patientId, request.relationship());
            var authorization = patientDataAccessService.recordNextOfKinApproval(
                    patientId,
                    new PatientDataAccessService.NextOfKinApprovalInput(
                            request.approverName(),
                            request.nextOfKinName(),
                            request.nextOfKinPhone(),
                            request.relationship(),
                            request.verificationMethod(),
                            request.expiresAt()
                    )
            );
            auditService.record("NEXT_OF_KIN_APPROVAL_RECORDED", patientId, "Next-of-kin approval captured");
            log.info("Next-of-kin approval granted patientId={} authorizationId={}", patientId, authorization.getId());
            return ResponseEntity.ok(PatientPortalController.AuthorizationView.from(authorization));
        } catch (RuntimeException e) {
            log.warn("Next-of-kin approval failed patientId={} reason={}", patientId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{patientId}/scope")
    public ResponseEntity<?> getScope(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            var scope = patientDataAccessService.resolveAccess(patientId);
            return ResponseEntity.ok(scope);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{patientId}/overview")
    public ResponseEntity<?> getOverview(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            log.info("Clinical patient overview request patientId={}", patientId);
            var access = patientDataAccessService.resolveAccess(patientId);

            if (access.scope() == dalili.com.base.domain.patient.model.PatientDataAuthorization.DataAccessScope.NONE) {
                return ResponseEntity.badRequest().body(new ErrorResponse("No active authorization to view patient data"));
            }

            if (access.scope() == dalili.com.base.domain.patient.model.PatientDataAuthorization.DataAccessScope.EMERGENCY_ONLY) {
                var emergency = patientEmergencyDataService.buildEmergencyData(patientId);
                auditService.record("CLINICIAN_PATIENT_DATA_VIEW", patientId, "Emergency-only overview accessed");
                return ResponseEntity.ok(Map.of(
                        "scope", access.scope().name(),
                        "reason", access.reason(),
                        "emergencyData", emergency
                ));
            }

            var payload = buildFullOverview(patientId, access.scope().name(), access.reason());
            auditService.record("CLINICIAN_PATIENT_DATA_VIEW", patientId, "Full overview accessed");
            log.info("Clinical patient overview delivered patientId={} scope={}", patientId, access.scope());
            return ResponseEntity.ok(payload);
        } catch (RuntimeException e) {
            log.warn("Clinical patient overview failed patientId={} reason={}", patientId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{patientId}/emergency")
    public ResponseEntity<?> getEmergencyData(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            var access = patientDataAccessService.resolveAccess(patientId);
            if (access.scope() == dalili.com.base.domain.patient.model.PatientDataAuthorization.DataAccessScope.NONE) {
                return ResponseEntity.badRequest().body(new ErrorResponse("No active authorization to view patient data"));
            }
            var emergency = patientEmergencyDataService.buildEmergencyData(patientId);
            auditService.record("CLINICIAN_PATIENT_DATA_VIEW", patientId, "Emergency data viewed");
            return ResponseEntity.ok(emergency);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/renewals/pending")
    public ResponseEntity<?> getPendingRenewals() {
        try {
            auditGuard.assertSessionActive();
            List<PrescriptionRenewalRequest> pending = prescriptionRenewalService.getClinicianPendingRequests();
            return ResponseEntity.ok(pending.stream().map(PatientPortalController.RenewalView::from).toList());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/renewals/{renewalRequestId}/review")
    public ResponseEntity<?> reviewRenewal(
            @PathVariable UUID renewalRequestId,
            @RequestBody ReviewRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            log.info("Clinical renewal review request renewalRequestId={} approve={}",
                    renewalRequestId, request.approve());
            PrescriptionRenewalRequest reviewed = prescriptionRenewalService.reviewRequest(
                    renewalRequestId,
                    request.approve(),
                    request.comments()
            );
            log.info("Clinical renewal reviewed renewalRequestId={} status={}",
                    reviewed.getId(), reviewed.getStatus());
            return ResponseEntity.ok(PatientPortalController.RenewalView.from(reviewed));
        } catch (RuntimeException e) {
            log.warn("Clinical renewal review failed renewalRequestId={} reason={}", renewalRequestId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/transfers/pending")
    public ResponseEntity<?> getPendingTransfers() {
        try {
            auditGuard.assertSessionActive();
            List<PatientDataTransferRequest> pending = patientDataTransferService.getPendingRequests();
            return ResponseEntity.ok(pending.stream().map(PatientPortalController.TransferRequestView::from).toList());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/transfers/{requestId}/review")
    public ResponseEntity<?> reviewTransfer(
            @PathVariable UUID requestId,
            @RequestBody ReviewRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            log.info("Clinical transfer review request requestId={} approve={}", requestId, request.approve());
            PatientDataTransferRequest reviewed = patientDataTransferService.reviewRequest(
                    requestId,
                    request.approve(),
                    request.comments()
            );
            log.info("Clinical transfer reviewed requestId={} status={}", reviewed.getId(), reviewed.getStatus());
            return ResponseEntity.ok(PatientPortalController.TransferRequestView.from(reviewed));
        } catch (RuntimeException e) {
            log.warn("Clinical transfer review failed requestId={} reason={}", requestId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/messages/inbox")
    public ResponseEntity<?> getInbox(@RequestParam(required = false) UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            var messages = portalMessagingService.getClinicianInbox(patientId).stream()
                    .map(PatientPortalController.PortalMessageView::from)
                    .toList();
            return ResponseEntity.ok(messages);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{patientId}/messages")
    public ResponseEntity<?> sendToPatient(
            @PathVariable UUID patientId,
            @RequestBody SendClinicianMessageRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            log.info("Clinical message send request patientId={} category={}", patientId, request.category());
            PortalMessage message = portalMessagingService.sendToPatient(
                    patientId,
                    new PortalMessagingService.ClinicianMessageInput(
                            request.category(),
                            request.subject(),
                            request.body(),
                            request.linkedEncounterId(),
                            request.linkedMedicationOrderId(),
                            request.linkedLabResultId(),
                            request.linkedReferralId()
                    )
            );
            log.info("Clinical message sent patientId={} messageId={}", patientId, message.getId());
            return ResponseEntity.ok(PatientPortalController.PortalMessageView.from(message));
        } catch (RuntimeException e) {
            log.warn("Clinical message send failed patientId={} reason={}", patientId, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/messages/{messageId}/read")
    public ResponseEntity<?> markMessageRead(@PathVariable UUID messageId) {
        try {
            auditGuard.assertSessionActive();
            PortalMessage message = portalMessagingService.markRead(messageId);
            return ResponseEntity.ok(PatientPortalController.PortalMessageView.from(message));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{patientId}/labs")
    public ResponseEntity<?> getLabs(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            return ResponseEntity.ok(clinicalRecordsService.getLabResults(patientId, 120).stream()
                    .map(PatientPortalController.LabResultView::from)
                    .toList());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{patientId}/labs")
    public ResponseEntity<?> addLab(
            @PathVariable UUID patientId,
            @RequestBody LabInputRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            var lab = clinicalRecordsService.addLabResult(
                    patientId,
                    new PatientClinicalRecordsService.LabResultInput(
                            request.encounterId(),
                            request.testName(),
                            request.resultValue(),
                            request.unit(),
                            request.referenceRange(),
                            request.interpretation(),
                            request.criticalResult()
                    )
            );
            return ResponseEntity.ok(PatientPortalController.LabResultView.from(lab));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{patientId}/referrals")
    public ResponseEntity<?> getReferrals(@PathVariable UUID patientId) {
        try {
            auditGuard.assertSessionActive();
            return ResponseEntity.ok(clinicalRecordsService.getReferrals(patientId, 120).stream()
                    .map(PatientPortalController.ReferralView::from)
                    .toList());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{patientId}/referrals")
    public ResponseEntity<?> addReferral(
            @PathVariable UUID patientId,
            @RequestBody ReferralInputRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            var referral = clinicalRecordsService.addReferral(
                    patientId,
                    new PatientClinicalRecordsService.ReferralInput(
                            request.encounterId(),
                            request.referredToFacility(),
                            request.specialty(),
                            request.reason(),
                            request.notes(),
                            request.destinationUsesDalili()
                    )
            );
            return ResponseEntity.ok(PatientPortalController.ReferralView.from(referral));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/referrals/{referralId}/status")
    public ResponseEntity<?> updateReferralStatus(
            @PathVariable UUID referralId,
            @RequestBody UpdateReferralStatusRequest request
    ) {
        try {
            auditGuard.assertSessionActive();
            var referral = clinicalRecordsService.updateReferralStatus(
                    referralId,
                    request.status(),
                    request.notes()
            );
            return ResponseEntity.ok(PatientPortalController.ReferralView.from(referral));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/referrals/{referralId}/printable")
    public ResponseEntity<?> getPrintableReferral(@PathVariable UUID referralId) {
        try {
            auditGuard.assertSessionActive();
            return ResponseEntity.ok(referralPrintService.buildPrintableReferral(referralId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/referrals/{referralId}/print")
    public ResponseEntity<?> markReferralPrinted(@PathVariable UUID referralId) {
        try {
            auditGuard.assertSessionActive();
            var referral = referralPrintService.markPrinted(referralId);
            return ResponseEntity.ok(PatientPortalController.ReferralView.from(referral));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    private Map<String, Object> buildFullOverview(UUID patientId, String scope, String reason) {
        var patient = patientService.findById(patientId);
        var queue = queueTicketRepository.findByPatientIdAndQueueDateBetweenOrderByQueueDateDesc(
                        patientId,
                        LocalDate.now().minusDays(90),
                        LocalDate.now()
                ).stream()
                .limit(40)
                .map(PatientPortalController.QueueTicketView::from)
                .toList();
        var encounters = encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .limit(40)
                .map(PatientPortalController.EncounterView::from)
                .toList();
        var notes = encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .limit(60)
                .map(PatientPortalController.EncounterNoteView::from)
                .toList();

        List<MedicationOrder> medications = medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId).stream()
                .limit(40)
                .toList();
        String allergies = patientEmergencyDataService.buildEmergencyData(patientId).knownAllergies();
        var warnings = medicationSafetyService.evaluateContraindications(medications, allergies);
        var medicationViews = medications.stream()
                .map(order -> PatientPortalController.MedicationWithWarningsView.from(
                        order,
                        warnings.getOrDefault(order.getId(), List.of())
                ))
                .toList();

        return Map.of(
                "scope", scope,
                "reason", reason,
                "profile", PatientPortalController.PatientProfileView.from(patient),
                "queue", queue,
                "encounters", encounters,
                "notes", notes,
                "medications", medicationViews,
                "labs", clinicalRecordsService.getLabResults(patientId, 120).stream().map(PatientPortalController.LabResultView::from).toList(),
                "referrals", clinicalRecordsService.getReferrals(patientId, 120).stream().map(PatientPortalController.ReferralView::from).toList(),
                "authorizations", patientDataAccessService.getAuthorizationHistory(patientId).stream()
                        .map(PatientPortalController.AuthorizationView::from)
                        .toList()
        );
    }

    public record EmergencyBreakGlassRequest(String justification) {
    }

    public record NextOfKinApprovalRequest(
            String approverName,
            String nextOfKinName,
            String nextOfKinPhone,
            String relationship,
            String verificationMethod,
            java.time.Instant expiresAt
    ) {
    }

    public record ReviewRequest(
            boolean approve,
            String comments
    ) {
    }

    public record SendClinicianMessageRequest(
            PortalMessage.MessageCategory category,
            String subject,
            String body,
            UUID linkedEncounterId,
            UUID linkedMedicationOrderId,
            UUID linkedLabResultId,
            UUID linkedReferralId
    ) {
    }

    public record LabInputRequest(
            UUID encounterId,
            String testName,
            String resultValue,
            String unit,
            String referenceRange,
            String interpretation,
            boolean criticalResult
    ) {
    }

    public record ReferralInputRequest(
            UUID encounterId,
            String referredToFacility,
            String specialty,
            String reason,
            String notes,
            Boolean destinationUsesDalili
    ) {
    }

    public record UpdateReferralStatusRequest(
            ReferralRecord.ReferralStatus status,
            String notes
    ) {
    }

    public record ErrorResponse(String error) {
    }
}


