package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Records requests to transfer patient data between facilities on the same platform.
 */
@Entity
@Table(name = "patient_data_transfer_requests", indexes = {
        @Index(name = "idx_transfer_patient", columnList = "patientId"),
        @Index(name = "idx_transfer_status", columnList = "status"),
        @Index(name = "idx_transfer_requested", columnList = "requestedAt")
})
@Getter
public class PatientDataTransferRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 100)
    private String sourceFacilityCode;

    @Column(nullable = false, length = 100)
    private String targetFacilityCode;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TransferStatus status;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false, length = 120)
    private String requestedById;

    @Column(nullable = false, length = 120)
    private String requestedByRole;

    @Column(length = 255)
    private String requestedByName;

    @Column(nullable = false)
    private boolean emergencyBlocked;

    @Column
    private UUID emergencyQueueTicketId;

    @Column
    private Boolean destinationUsesDalili;

    @Column
    private UUID linkedReferralId;

    @Column
    private Instant reviewedAt;

    @Column(length = 120)
    private String reviewedById;

    @Column(length = 255)
    private String reviewedByName;

    @Column(length = 500)
    private String reviewNotes;

    protected PatientDataTransferRequest() {
    }

    public static PatientDataTransferRequest create(
            UUID patientId,
            String sourceFacilityCode,
            String targetFacilityCode,
            String reason,
            String requestedById,
            String requestedByRole,
            String requestedByName,
            boolean emergencyBlocked,
            UUID emergencyQueueTicketId,
            Boolean destinationUsesDalili,
            UUID linkedReferralId
    ) {
        PatientDataTransferRequest request = new PatientDataTransferRequest();
        request.patientId = patientId;
        request.sourceFacilityCode = require(sourceFacilityCode, "sourceFacilityCode", 100);
        request.targetFacilityCode = require(targetFacilityCode, "targetFacilityCode", 100);
        request.reason = normalize(reason, 500);
        request.requestedById = require(requestedById, "requestedById", 120);
        request.requestedByRole = require(requestedByRole, "requestedByRole", 120);
        request.requestedByName = normalize(requestedByName, 255);
        request.emergencyBlocked = emergencyBlocked;
        request.emergencyQueueTicketId = emergencyQueueTicketId;
        request.destinationUsesDalili = destinationUsesDalili;
        request.linkedReferralId = linkedReferralId;
        request.status = emergencyBlocked ? TransferStatus.BLOCKED_EMERGENCY : TransferStatus.PENDING;
        request.requestedAt = Instant.now();
        return request;
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

    public void approve(String reviewedById, String reviewedByName, String reviewNotes) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Only pending requests can be approved");
        }
        this.status = TransferStatus.APPROVED;
        this.reviewedAt = Instant.now();
        this.reviewedById = normalize(reviewedById, 120);
        this.reviewedByName = normalize(reviewedByName, 255);
        this.reviewNotes = normalize(reviewNotes, 500);
    }

    public void reject(String reviewedById, String reviewedByName, String reviewNotes) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException("Only pending requests can be rejected");
        }
        this.status = TransferStatus.REJECTED;
        this.reviewedAt = Instant.now();
        this.reviewedById = normalize(reviewedById, 120);
        this.reviewedByName = normalize(reviewedByName, 255);
        this.reviewNotes = normalize(reviewNotes, 500);
    }

    public enum TransferStatus {
        PENDING,
        APPROVED,
        REJECTED,
        BLOCKED_EMERGENCY
    }
}
