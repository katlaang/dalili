package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.PortalMessage;
import dalili.com.base.domain.patient.repository.PortalMessageRepository;
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
 * Handles patient portal messaging between patients and clinicians.
 */
@Service
public class PortalMessagingService {

    private static final Logger log = LoggerFactory.getLogger(PortalMessagingService.class);

    private final PortalMessageRepository messageRepository;
    private final PatientService patientService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public PortalMessagingService(
            PortalMessageRepository messageRepository,
            PatientService patientService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.messageRepository = messageRepository;
        this.patientService = patientService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public PortalMessage sendFromPatient(UUID patientId, PatientMessageInput input) {
        auditGuard.assertSessionActive();
        if (input == null) {
            throw new MessageException("Message payload is required");
        }
        log.info("Patient portal message create request patientId={} senderType=PATIENT", patientId);

        String patientName = patientService.findById(patientId).getFullName();
        PortalMessage message = PortalMessage.fromPatient(
                patientId,
                sessionContext.username(),
                patientName,
                input.category(),
                input.subject(),
                input.body(),
                input.targetStaffId()
        );
        message.linkResources(input.linkedEncounterId(), input.linkedMedicationOrderId(), input.linkedLabResultId(), input.linkedReferralId());
        message = messageRepository.save(message);

        auditService.record(
                "PATIENT_PORTAL_MESSAGE_SENT",
                patientId,
                "Patient sent message to clinic. category=" + message.getCategory()
        );
        log.info("Patient portal message created messageId={} patientId={} category={}",
                message.getId(), patientId, message.getCategory());
        return message;
    }

    @Transactional
    public PortalMessage sendToPatient(UUID patientId, ClinicianMessageInput input) {
        auditGuard.assertSessionActive();
        assertPortalSender();

        if (input == null) {
            throw new MessageException("Message payload is required");
        }
        log.info("Facility portal message create request patientId={} senderRole={}", patientId, sessionContext.role());

        PortalMessage message = PortalMessage.toPatient(
                patientId,
                sessionContext.username(),
                sessionContext.role() != null ? sessionContext.role().name() : "STAFF",
                sessionContext.username(),
                input.category(),
                input.subject(),
                input.body()
        );
        message.linkResources(input.linkedEncounterId(), input.linkedMedicationOrderId(), input.linkedLabResultId(), input.linkedReferralId());
        message = messageRepository.save(message);

        auditService.record(
                "PATIENT_PORTAL_MESSAGE_SENT",
                patientId,
                "Clinician sent message to patient. category=" + message.getCategory()
        );
        log.info("Facility portal message created messageId={} patientId={} category={}",
                message.getId(), patientId, message.getCategory());
        return message;
    }

    public List<PortalMessage> getPatientMessages(UUID patientId) {
        auditGuard.assertSessionActive();
        List<PortalMessage> messages = messageRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        auditService.record("PATIENT_PORTAL_MESSAGES_VIEWED", patientId,
                "Patient viewed portal messages count=" + messages.size());
        log.info("Patient portal messages fetched patientId={} count={}", patientId, messages.size());
        return messages;
    }

    public List<PortalMessage> getClinicianInbox(UUID patientIdFilter) {
        auditGuard.assertSessionActive();
        assertPortalSender();
        List<PortalMessage> messages = messageRepository.findClinicianInbox(sessionContext.username(), patientIdFilter);
        auditService.record("CLINICIAN_PORTAL_INBOX_VIEWED", patientIdFilter,
                "Clinician viewed patient portal inbox count=" + messages.size());
        log.info("Clinician portal inbox fetched staffId={} filterPatientId={} count={}",
                sessionContext.username(), patientIdFilter, messages.size());
        return messages;
    }

    @Transactional
    public PortalMessage markRead(UUID messageId) {
        auditGuard.assertSessionActive();
        log.info("Marking portal message as read messageId={} reader={}", messageId, sessionContext.username());
        PortalMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageException("Message not found"));
        message.markRead(sessionContext.username());
        message = messageRepository.save(message);

        auditService.record("PATIENT_PORTAL_MESSAGE_READ", message.getPatientId(),
                "Message marked as read: " + messageId);
        log.info("Portal message marked as read messageId={} patientId={}", message.getId(), message.getPatientId());
        return message;
    }

    private void assertPortalSender() {
        Role role = sessionContext.role();
        if (role != Role.ADMIN
                && role != Role.PHARMACIST
                && role != Role.LAB_TECHNICIAN
                && role != Role.RECEPTIONIST) {
            throw new MessageException("Only facility operations roles can send or review portal messages");
        }
    }

    public record PatientMessageInput(
            PortalMessage.MessageCategory category,
            String subject,
            String body,
            String targetStaffId,
            UUID linkedEncounterId,
            UUID linkedMedicationOrderId,
            UUID linkedLabResultId,
            UUID linkedReferralId
    ) {
    }

    public record ClinicianMessageInput(
            PortalMessage.MessageCategory category,
            String subject,
            String body,
            UUID linkedEncounterId,
            UUID linkedMedicationOrderId,
            UUID linkedLabResultId,
            UUID linkedReferralId
    ) {
    }

    public static class MessageException extends RuntimeException {
        public MessageException(String message) {
            super(message);
        }
    }
}


