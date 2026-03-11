package dalili.com.base.domain.patient.model;

import dalili.com.base.domain.encounter.model.MedicationOrder;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks patient renewal requests for existing prescription orders.
 */
@Entity
@Table(name = "prescription_renewal_requests", indexes = {
        @Index(name = "idx_renewal_patient", columnList = "patientId"),
        @Index(name = "idx_renewal_order", columnList = "medicationOrderId"),
        @Index(name = "idx_renewal_status", columnList = "status"),
        @Index(name = "idx_renewal_requested", columnList = "requestedAt")
})
@Getter
public class PrescriptionRenewalRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private UUID medicationOrderId;

    @Column(nullable = false, length = 255)
    private String medicationName;

    @Column(nullable = false, length = 120)
    private String dosage;

    @Column(nullable = false, length = 120)
    private String frequency;

    @Column(length = 120)
    private String orderedByStaffId;

    @Column(length = 255)
    private String orderedByStaffName;

    @Column(nullable = false, length = 120)
    private String requestedByPatientUserId;

    @Column(length = 500)
    private String requestNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RenewalStatus status;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column
    private Instant reviewedAt;

    @Column(length = 120)
    private String reviewedByStaffId;

    @Column(length = 255)
    private String reviewedByStaffName;

    @Column(length = 500)
    private String reviewComments;

    protected PrescriptionRenewalRequest() {
    }

    public static PrescriptionRenewalRequest create(
            UUID patientId,
            MedicationOrder medicationOrder,
            String requestedByPatientUserId,
            String requestNote
    ) {
        PrescriptionRenewalRequest request = new PrescriptionRenewalRequest();
        request.patientId = patientId;
        request.medicationOrderId = medicationOrder.getId();
        request.medicationName = require(medicationOrder.getMedicationName(), "medicationName", 255);
        request.dosage = require(medicationOrder.getDosage(), "dosage", 120);
        request.frequency = require(medicationOrder.getFrequency(), "frequency", 120);
        request.orderedByStaffId = normalize(medicationOrder.getOrderedBy(), 120);
        request.orderedByStaffName = normalize(medicationOrder.getOrderedByName(), 255);
        request.requestedByPatientUserId = require(requestedByPatientUserId, "requestedByPatientUserId", 120);
        request.requestNote = normalize(requestNote, 500);
        request.status = RenewalStatus.REQUESTED;
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

    public void approve(String reviewedByStaffId, String reviewedByStaffName, String comments) {
        if (this.status != RenewalStatus.REQUESTED) {
            throw new IllegalStateException("Only requested renewals can be approved");
        }
        this.status = RenewalStatus.APPROVED;
        this.reviewedAt = Instant.now();
        this.reviewedByStaffId = normalize(reviewedByStaffId, 120);
        this.reviewedByStaffName = normalize(reviewedByStaffName, 255);
        this.reviewComments = normalize(comments, 500);
    }

    public void reject(String reviewedByStaffId, String reviewedByStaffName, String comments) {
        if (this.status != RenewalStatus.REQUESTED) {
            throw new IllegalStateException("Only requested renewals can be rejected");
        }
        this.status = RenewalStatus.REJECTED;
        this.reviewedAt = Instant.now();
        this.reviewedByStaffId = normalize(reviewedByStaffId, 120);
        this.reviewedByStaffName = normalize(reviewedByStaffName, 255);
        this.reviewComments = normalize(comments, 500);
    }

    public enum RenewalStatus {
        REQUESTED,
        APPROVED,
        REJECTED
    }
}

