package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Captures explicit authorization context for patient data access.
 *
 * <p>This supports patient consent, next-of-kin approval, emergency break-glass,
 * and same-hospital admission grants.</p>
 */
@Entity
@Table(name = "patient_data_authorizations", indexes = {
        @Index(name = "idx_auth_patient", columnList = "patientId"),
        @Index(name = "idx_auth_facility", columnList = "facilityCode"),
        @Index(name = "idx_auth_type", columnList = "authorizationType"),
        @Index(name = "idx_auth_granted", columnList = "grantedAt")
})
@Getter
public class PatientDataAuthorization {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 100)
    private String facilityCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuthorizationType authorizationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DataAccessScope dataAccessScope;

    @Column(nullable = false, length = 40)
    private String authorizerRole;

    @Column(nullable = false, length = 255)
    private String authorizerName;

    @Column(nullable = false, length = 120)
    private String authorizerId;

    @Column(length = 255)
    private String nextOfKinName;

    @Column(length = 120)
    private String nextOfKinPhone;

    @Column(length = 120)
    private String nextOfKinRelationship;

    @Column(length = 120)
    private String nextOfKinVerificationMethod;

    @Column(length = 1000)
    private String emergencyJustification;

    @Column
    private UUID triageAssessmentId;

    @Column
    private UUID linkedAdmissionTicketId;

    @Column(nullable = false)
    private Instant grantedAt;

    @Column
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column
    private Instant revokedAt;

    @Column(length = 500)
    private String revocationReason;

    @Column(length = 120)
    private String revokedBy;

    protected PatientDataAuthorization() {
    }

    public static PatientDataAuthorization grantPatientConsent(
            UUID patientId,
            String facilityCode,
            String patientUserId,
            String patientName
    ) {
        return createBase(
                patientId,
                facilityCode,
                AuthorizationType.PATIENT_CONSENT,
                DataAccessScope.FULL_ACCESS,
                "PATIENT",
                patientName,
                patientUserId
        );
    }

    public static PatientDataAuthorization grantNextOfKinApproval(
            UUID patientId,
            String facilityCode,
            String approverId,
            String approverName,
            String nextOfKinName,
            String nextOfKinPhone,
            String nextOfKinRelationship,
            String verificationMethod,
            Instant expiresAt
    ) {
        PatientDataAuthorization authorization = createBase(
                patientId,
                facilityCode,
                AuthorizationType.NEXT_OF_KIN_APPROVAL,
                DataAccessScope.FULL_ACCESS,
                "NEXT_OF_KIN",
                approverName,
                approverId
        );
        authorization.nextOfKinName = normalize(nextOfKinName, 255);
        authorization.nextOfKinPhone = normalize(nextOfKinPhone, 120);
        authorization.nextOfKinRelationship = normalize(nextOfKinRelationship, 120);
        authorization.nextOfKinVerificationMethod = normalize(verificationMethod, 120);
        authorization.expiresAt = expiresAt;
        return authorization;
    }

    public static PatientDataAuthorization grantEmergencyBreakGlass(
            UUID patientId,
            String facilityCode,
            String clinicianId,
            String clinicianName,
            String clinicianRole,
            String emergencyJustification,
            UUID triageAssessmentId
    ) {
        PatientDataAuthorization authorization = createBase(
                patientId,
                facilityCode,
                AuthorizationType.EMERGENCY_BREAK_GLASS,
                DataAccessScope.EMERGENCY_ONLY,
                clinicianRole,
                clinicianName,
                clinicianId
        );
        authorization.emergencyJustification = normalize(emergencyJustification, 1000);
        authorization.triageAssessmentId = triageAssessmentId;
        return authorization;
    }

    public static PatientDataAuthorization grantAdmissionAccess(
            UUID patientId,
            String facilityCode,
            String staffId,
            String staffName,
            UUID admissionTicketId
    ) {
        PatientDataAuthorization authorization = createBase(
                patientId,
                facilityCode,
                AuthorizationType.ADMISSION_GRANT,
                DataAccessScope.FULL_ACCESS,
                "SYSTEM",
                staffName,
                staffId
        );
        authorization.linkedAdmissionTicketId = admissionTicketId;
        return authorization;
    }

    private static PatientDataAuthorization createBase(
            UUID patientId,
            String facilityCode,
            AuthorizationType authorizationType,
            DataAccessScope dataAccessScope,
            String authorizerRole,
            String authorizerName,
            String authorizerId
    ) {
        PatientDataAuthorization authorization = new PatientDataAuthorization();
        authorization.patientId = patientId;
        authorization.facilityCode = require(facilityCode, "facilityCode", 100);
        authorization.authorizationType = authorizationType;
        authorization.dataAccessScope = dataAccessScope;
        authorization.authorizerRole = require(authorizerRole, "authorizerRole", 40);
        authorization.authorizerName = require(authorizerName, "authorizerName", 255);
        authorization.authorizerId = require(authorizerId, "authorizerId", 120);
        authorization.grantedAt = Instant.now();
        authorization.revoked = false;
        return authorization;
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

    public void revoke(String reason, String revokedBy) {
        this.revoked = true;
        this.revokedAt = Instant.now();
        this.revocationReason = normalize(reason, 500);
        this.revokedBy = normalize(revokedBy, 120);
    }

    public boolean isActive() {
        if (revoked) {
            return false;
        }
        if (expiresAt == null) {
            return true;
        }
        return expiresAt.isAfter(Instant.now());
    }

    public enum AuthorizationType {
        PATIENT_CONSENT,
        NEXT_OF_KIN_APPROVAL,
        EMERGENCY_BREAK_GLASS,
        ADMISSION_GRANT
    }

    public enum DataAccessScope {
        NONE,
        EMERGENCY_ONLY,
        FULL_ACCESS
    }
}

