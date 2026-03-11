package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Patient portal message exchanged between patient and clinical staff.
 */
@Entity
@Table(name = "portal_messages", indexes = {
        @Index(name = "idx_portal_message_patient", columnList = "patientId"),
        @Index(name = "idx_portal_message_created", columnList = "createdAt"),
        @Index(name = "idx_portal_message_recipient", columnList = "recipientId")
})
@Getter
public class PortalMessage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MessageCategory category;

    @Column(nullable = false, length = 120)
    private String senderId;

    @Column(nullable = false, length = 120)
    private String senderRole;

    @Column(nullable = false, length = 255)
    private String senderName;

    @Column(length = 120)
    private String recipientId;

    @Column(length = 120)
    private String recipientRole;

    @Column(length = 255)
    private String recipientName;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column
    private UUID linkedEncounterId;

    @Column
    private UUID linkedMedicationOrderId;

    @Column
    private UUID linkedLabResultId;

    @Column
    private UUID linkedReferralId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant readAt;

    @Column(length = 120)
    private String readById;

    protected PortalMessage() {
    }

    public static PortalMessage fromPatient(
            UUID patientId,
            String patientUserId,
            String patientName,
            MessageCategory category,
            String subject,
            String body,
            String targetStaffId
    ) {
        PortalMessage message = new PortalMessage();
        message.patientId = patientId;
        message.direction = MessageDirection.FROM_PATIENT;
        message.category = category != null ? category : MessageCategory.GENERAL;
        message.senderId = require(patientUserId, "patientUserId", 120);
        message.senderRole = "PATIENT";
        message.senderName = require(patientName, "patientName", 255);
        message.recipientId = normalize(targetStaffId, 120);
        message.recipientRole = "STAFF";
        message.subject = normalize(subject, 200);
        message.body = require(body, "body", 4000);
        message.createdAt = Instant.now();
        return message;
    }

    public static PortalMessage toPatient(
            UUID patientId,
            String staffId,
            String staffRole,
            String staffName,
            MessageCategory category,
            String subject,
            String body
    ) {
        PortalMessage message = new PortalMessage();
        message.patientId = patientId;
        message.direction = MessageDirection.TO_PATIENT;
        message.category = category != null ? category : MessageCategory.GENERAL;
        message.senderId = require(staffId, "staffId", 120);
        message.senderRole = require(staffRole, "staffRole", 120);
        message.senderName = require(staffName, "staffName", 255);
        message.recipientRole = "PATIENT";
        message.subject = normalize(subject, 200);
        message.body = require(body, "body", 4000);
        message.createdAt = Instant.now();
        return message;
    }

    private static String require(String value, String field, int maxLen) {
        String normalized = normalize(value, maxLen);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    public void linkResources(
            UUID linkedEncounterId,
            UUID linkedMedicationOrderId,
            UUID linkedLabResultId,
            UUID linkedReferralId
    ) {
        this.linkedEncounterId = linkedEncounterId;
        this.linkedMedicationOrderId = linkedMedicationOrderId;
        this.linkedLabResultId = linkedLabResultId;
        this.linkedReferralId = linkedReferralId;
    }

    public void markRead(String readerId) {
        this.readAt = Instant.now();
        this.readById = normalize(readerId, 120);
    }

    public enum MessageDirection {
        TO_PATIENT,
        FROM_PATIENT
    }

    public enum MessageCategory {
        GENERAL,
        PRESCRIPTION,
        LAB_RESULT,
        APPOINTMENT,
        REFERRAL
    }
}

