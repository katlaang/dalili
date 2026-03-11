package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted referral entry for patient longitudinal records.
 */
@Entity
@Table(name = "referral_records", indexes = {
        @Index(name = "idx_referral_patient", columnList = "patientId"),
        @Index(name = "idx_referral_encounter", columnList = "encounterId"),
        @Index(name = "idx_referral_created", columnList = "referredAt")
})
@Getter
public class ReferralRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column
    private UUID encounterId;

    @Column(nullable = false, length = 255)
    private String referredToFacility;

    @Column(nullable = false, length = 255)
    private String specialty;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReferralStatus status;

    @Column(nullable = false)
    private Instant referredAt;

    @Column(nullable = false, length = 120)
    private String referredById;

    @Column(nullable = false, length = 255)
    private String referredByName;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReferralOutputFormat outputFormat;

    @Column
    private Boolean destinationUsesDalili;

    @Column
    private Instant printedAt;

    @Column(length = 120)
    private String printedById;

    @Column(length = 255)
    private String printedByName;

    protected ReferralRecord() {
    }

    public static ReferralRecord create(
            UUID patientId,
            UUID encounterId,
            String referredToFacility,
            String specialty,
            String reason,
            String referredById,
            String referredByName,
            String notes,
            Boolean destinationUsesDalili
    ) {
        ReferralRecord record = new ReferralRecord();
        record.patientId = patientId;
        record.encounterId = encounterId;
        record.referredToFacility = require(referredToFacility, "referredToFacility", 255);
        record.specialty = require(specialty, "specialty", 255);
        record.reason = require(reason, "reason", 1000);
        record.referredById = require(referredById, "referredById", 120);
        record.referredByName = require(referredByName, "referredByName", 255);
        record.notes = normalize(notes, 1000);
        record.destinationUsesDalili = destinationUsesDalili != null ? destinationUsesDalili : Boolean.TRUE;
        record.outputFormat = Boolean.TRUE.equals(record.destinationUsesDalili)
                ? ReferralOutputFormat.ELECTRONIC
                : ReferralOutputFormat.PRINTED;
        record.referredAt = Instant.now();
        record.status = ReferralStatus.PENDING;
        return record;
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

    public void updateStatus(ReferralStatus status, String notes) {
        if (status == null) {
            return;
        }
        this.status = status;
        this.notes = normalize(notes, 1000);
    }

    public void markPrinted(String printedById, String printedByName) {
        this.printedById = normalize(printedById, 120);
        this.printedByName = normalize(printedByName, 255);
        this.printedAt = Instant.now();
        this.outputFormat = ReferralOutputFormat.PRINTED;
    }

    public enum ReferralStatus {
        PENDING,
        ACCEPTED,
        COMPLETED,
        CANCELLED
    }

    public enum ReferralOutputFormat {
        ELECTRONIC,
        PRINTED
    }
}
