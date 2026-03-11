package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted lab result summary for patient longitudinal records.
 */
@Entity
@Table(name = "lab_result_records", indexes = {
        @Index(name = "idx_lab_record_patient", columnList = "patientId"),
        @Index(name = "idx_lab_record_encounter", columnList = "encounterId"),
        @Index(name = "idx_lab_record_recorded", columnList = "recordedAt")
})
@Getter
public class LabResultRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column
    private UUID encounterId;

    @Column(nullable = false, length = 255)
    private String testName;

    @Column(length = 255)
    private String resultValue;

    @Column(length = 120)
    private String unit;

    @Column(length = 255)
    private String referenceRange;

    @Column(length = 1000)
    private String interpretation;

    @Column(nullable = false)
    private boolean criticalResult;

    @Column(nullable = false)
    private Instant recordedAt;

    @Column(nullable = false, length = 120)
    private String recordedById;

    @Column(nullable = false, length = 255)
    private String recordedByName;

    protected LabResultRecord() {
    }

    public static LabResultRecord create(
            UUID patientId,
            UUID encounterId,
            String testName,
            String resultValue,
            String unit,
            String referenceRange,
            String interpretation,
            boolean criticalResult,
            String recordedById,
            String recordedByName
    ) {
        LabResultRecord record = new LabResultRecord();
        record.patientId = patientId;
        record.encounterId = encounterId;
        record.testName = require(testName, "testName", 255);
        record.resultValue = normalize(resultValue, 255);
        record.unit = normalize(unit, 120);
        record.referenceRange = normalize(referenceRange, 255);
        record.interpretation = normalize(interpretation, 1000);
        record.criticalResult = criticalResult;
        record.recordedById = require(recordedById, "recordedById", 120);
        record.recordedByName = require(recordedByName, "recordedByName", 255);
        record.recordedAt = Instant.now();
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
}

