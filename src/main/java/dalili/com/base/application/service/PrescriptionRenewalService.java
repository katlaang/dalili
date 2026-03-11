package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.MedicationOrderRepository;
import dalili.com.base.domain.patient.model.PrescriptionRenewalRequest;
import dalili.com.base.domain.patient.repository.PrescriptionRenewalRequestRepository;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Handles patient-driven prescription renewal requests and clinician decisions.
 */
@Service
public class PrescriptionRenewalService {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionRenewalService.class);

    private final PrescriptionRenewalRequestRepository renewalRepository;
    private final MedicationOrderRepository medicationOrderRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public PrescriptionRenewalService(
            PrescriptionRenewalRequestRepository renewalRepository,
            MedicationOrderRepository medicationOrderRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.renewalRepository = renewalRepository;
        this.medicationOrderRepository = medicationOrderRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public PrescriptionRenewalRequest requestRenewal(UUID patientId, UUID medicationOrderId, String note) {
        auditGuard.assertSessionActive();
        log.info("Prescription renewal requested patientId={} medicationOrderId={}", patientId, medicationOrderId);

        MedicationOrder medicationOrder = medicationOrderRepository.findById(medicationOrderId)
                .orElseThrow(() -> new RenewalException("Medication order not found"));

        if (!medicationOrder.getPatientId().equals(patientId)) {
            throw new RenewalException("Medication order does not belong to current patient");
        }

        if (renewalRepository.existsByMedicationOrderIdAndStatus(
                medicationOrderId,
                PrescriptionRenewalRequest.RenewalStatus.REQUESTED
        )) {
            throw new RenewalException("A pending renewal request already exists for this medication");
        }

        PrescriptionRenewalRequest request = PrescriptionRenewalRequest.create(
                patientId,
                medicationOrder,
                sessionContext.username(),
                note
        );
        request = renewalRepository.save(request);

        auditService.record("PRESCRIPTION_RENEWAL_REQUESTED", patientId,
                String.format("Renewal requested for medicationOrder=%s medication=%s",
                        medicationOrderId, medicationOrder.getMedicationName()));
        log.info("Prescription renewal request created requestId={} patientId={}", request.getId(), patientId);

        return request;
    }

    public List<PrescriptionRenewalRequest> getPatientRenewalRequests(UUID patientId) {
        auditGuard.assertSessionActive();
        List<PrescriptionRenewalRequest> requests = renewalRepository.findByPatientIdOrderByRequestedAtDesc(patientId);
        auditService.record("PRESCRIPTION_RENEWAL_HISTORY_VIEWED", patientId,
                "Patient viewed renewal request history count=" + requests.size());
        log.info("Patient renewal history fetched patientId={} count={}", patientId, requests.size());
        return requests;
    }

    public List<PrescriptionRenewalRequest> getClinicianPendingRequests() {
        auditGuard.assertSessionActive();
        assertReviewerRole();
        log.info("Loading clinician pending renewal requests role={} reviewer={}", sessionContext.role(), sessionContext.username());

        if (sessionContext.role() == Role.ADMIN || sessionContext.role() == Role.PHARMACIST) {
            List<PrescriptionRenewalRequest> requests =
                    renewalRepository.findByStatusOrderByRequestedAtAsc(PrescriptionRenewalRequest.RenewalStatus.REQUESTED);
            log.info("Loaded pending renewal requests count={} scope=global", requests.size());
            return requests;
        }
        List<PrescriptionRenewalRequest> requests = renewalRepository.findByStatusAndOrderedByStaffIdOrderByRequestedAtAsc(
                PrescriptionRenewalRequest.RenewalStatus.REQUESTED,
                sessionContext.username()
        );
        log.info("Loaded pending renewal requests count={} scope=assigned reviewer={}",
                requests.size(), sessionContext.username());
        return requests;
    }

    @Transactional
    public PrescriptionRenewalRequest reviewRequest(UUID renewalRequestId, boolean approve, String comments) {
        auditGuard.assertSessionActive();
        assertReviewerRole();
        log.info("Reviewing prescription renewal request requestId={} approve={} reviewer={}",
                renewalRequestId, approve, sessionContext.username());

        PrescriptionRenewalRequest request = renewalRepository.findById(renewalRequestId)
                .orElseThrow(() -> new RenewalException("Renewal request not found"));

        if (sessionContext.role() == Role.PHYSICIAN
                && request.getOrderedByStaffId() != null
                && !request.getOrderedByStaffId().equalsIgnoreCase(sessionContext.username())) {
            throw new RenewalException("You are not assigned to review this renewal request");
        }

        try {
            if (approve) {
                request.approve(sessionContext.username(), sessionContext.username(), comments);
            } else {
                request.reject(sessionContext.username(), sessionContext.username(), comments);
            }
        } catch (IllegalStateException e) {
            throw new RenewalException(e.getMessage());
        }

        request = renewalRepository.save(request);

        auditService.record(
                approve ? "PRESCRIPTION_RENEWAL_APPROVED" : "PRESCRIPTION_RENEWAL_REJECTED",
                request.getPatientId(),
                String.format("renewalRequest=%s comments=%s", renewalRequestId, comments)
        );
        log.info("Prescription renewal request reviewed requestId={} patientId={} status={}",
                request.getId(), request.getPatientId(), request.getStatus());

        return request;
    }

    private void assertReviewerRole() {
        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.ADMIN && role != Role.PHARMACIST) {
            throw new RenewalException("Only physicians/pharmacists/admin can review prescription renewal requests");
        }
    }

    public static class RenewalException extends RuntimeException {
        public RenewalException(String message) {
            super(message);
        }
    }
}


