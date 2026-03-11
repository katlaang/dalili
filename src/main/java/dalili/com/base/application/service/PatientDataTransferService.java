package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.PatientDataTransferRequest;
import dalili.com.base.domain.patient.model.ReferralRecord;
import dalili.com.base.domain.patient.repository.PatientDataTransferRequestRepository;
import dalili.com.base.domain.patient.repository.ReferralRecordRepository;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Handles cross-facility patient data transfer requests.
 */
@Service
public class PatientDataTransferService {

    private static final Logger log = LoggerFactory.getLogger(PatientDataTransferService.class);

    private final PatientDataTransferRequestRepository requestRepository;
    private final ReferralRecordRepository referralRepository;
    private final QueueTicketRepository queueTicketRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public PatientDataTransferService(
            PatientDataTransferRequestRepository requestRepository,
            ReferralRecordRepository referralRepository,
            QueueTicketRepository queueTicketRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.requestRepository = requestRepository;
        this.referralRepository = referralRepository;
        this.queueTicketRepository = queueTicketRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public PatientDataTransferRequest requestTransfer(UUID patientId, RequestInput input) {
        auditGuard.assertSessionActive();
        if (input == null) {
            throw new TransferException("Transfer request payload is required");
        }

        QueueTicket activeEmergency = findActiveEmergencyTicket(patientId);
        boolean emergencyBlocked = activeEmergency != null;
        Boolean destinationUsesDalili = input.destinationUsesDalili() != null ? input.destinationUsesDalili() : Boolean.TRUE;

        UUID linkedReferralId = null;
        if (!Boolean.TRUE.equals(destinationUsesDalili)) {
            ReferralRecord referral = ReferralRecord.create(
                    patientId,
                    null,
                    input.targetFacilityCode(),
                    "TRANSFER_REQUEST",
                    input.reason() != null ? input.reason() : "Patient requested facility transfer",
                    sessionContext.username(),
                    sessionContext.username(),
                    "Referral generated from patient transfer request",
                    false
            );
            referral = referralRepository.save(referral);
            linkedReferralId = referral.getId();
        }

        PatientDataTransferRequest request = PatientDataTransferRequest.create(
                patientId,
                input.sourceFacilityCode(),
                input.targetFacilityCode(),
                input.reason(),
                sessionContext.username(),
                sessionContext.role() != null ? sessionContext.role().name() : "UNKNOWN",
                sessionContext.username(),
                emergencyBlocked,
                activeEmergency != null ? activeEmergency.getId() : null,
                destinationUsesDalili,
                linkedReferralId
        );

        request = requestRepository.save(request);
        log.info("Patient data transfer requested requestId={} patientId={} destinationUsesDalili={} linkedReferralId={}",
                request.getId(), patientId, destinationUsesDalili, linkedReferralId);

        auditService.record(
                emergencyBlocked ? "PATIENT_DATA_TRANSFER_BLOCKED" : "PATIENT_DATA_TRANSFER_REQUESTED",
                patientId,
                String.format("source=%s target=%s blockedEmergency=%s destinationUsesDalili=%s linkedReferralId=%s",
                        request.getSourceFacilityCode(),
                        request.getTargetFacilityCode(),
                        emergencyBlocked,
                        destinationUsesDalili,
                        linkedReferralId)
        );

        return request;
    }

    public List<PatientDataTransferRequest> getPatientRequests(UUID patientId) {
        auditGuard.assertSessionActive();
        List<PatientDataTransferRequest> requests = requestRepository.findByPatientIdOrderByRequestedAtDesc(patientId);
        auditService.record("PATIENT_DATA_TRANSFER_HISTORY_VIEWED", patientId,
                "Transfer request history viewed count=" + requests.size());
        return requests;
    }

    public List<PatientDataTransferRequest> getPendingRequests() {
        auditGuard.assertSessionActive();
        assertClinicalReviewer();
        List<PatientDataTransferRequest> requests =
                requestRepository.findByStatusOrderByRequestedAtAsc(PatientDataTransferRequest.TransferStatus.PENDING);
        UUID patientId = requests.isEmpty() ? null : requests.get(0).getPatientId();
        auditService.record("PATIENT_DATA_TRANSFER_PENDING_VIEWED", patientId,
                "Pending transfer queue viewed count=" + requests.size());
        return requests;
    }

    @Transactional
    public PatientDataTransferRequest reviewRequest(UUID requestId, boolean approve, String notes) {
        auditGuard.assertSessionActive();
        assertClinicalReviewer();

        PatientDataTransferRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new TransferException("Transfer request not found"));

        try {
            if (approve) {
                request.approve(sessionContext.username(), sessionContext.username(), notes);
            } else {
                request.reject(sessionContext.username(), sessionContext.username(), notes);
            }
        } catch (IllegalStateException e) {
            throw new TransferException(e.getMessage());
        }

        request = requestRepository.save(request);
        log.info("Patient data transfer reviewed requestId={} patientId={} approved={}",
                request.getId(), request.getPatientId(), approve);

        auditService.record(
                approve ? "PATIENT_DATA_TRANSFER_APPROVED" : "PATIENT_DATA_TRANSFER_REJECTED",
                request.getPatientId(),
                String.format("requestId=%s notes=%s", requestId, notes)
        );

        return request;
    }

    private QueueTicket findActiveEmergencyTicket(UUID patientId) {
        List<QueueTicket.QueueStatus> statuses = List.of(
                QueueTicket.QueueStatus.WAITING,
                QueueTicket.QueueStatus.CALLED,
                QueueTicket.QueueStatus.IN_PROGRESS
        );

        return queueTicketRepository.findByPatientIdAndQueueDateAndStatusInAndCategory(
                        patientId,
                        LocalDate.now(),
                        statuses,
                        QueueTicket.QueueCategory.EMERGENCY
                ).stream()
                .findFirst()
                .orElse(null);
    }

    private void assertClinicalReviewer() {
        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.ADMIN) {
            throw new TransferException("Only physician/admin can review transfer requests");
        }
    }

    public record RequestInput(
            String sourceFacilityCode,
            String targetFacilityCode,
            String reason,
            Boolean destinationUsesDalili
    ) {
    }

    public static class TransferException extends RuntimeException {
        public TransferException(String message) {
            super(message);
        }
    }
}
