package dalili.com.base.domain.patient.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients", indexes = {
        @Index(name = "idx_patient_mrn", columnList = "mrn", unique = true),
        @Index(name = "idx_patient_national_id", columnList = "nationalId")
})
@Getter
public class Patient {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String mrn;

    @Column
    @Setter
    private String nationalId;

    @Column(nullable = false)
    private String givenName;

    @Column(nullable = false)
    private String familyName;

    @Column
    @Setter
    private String middleName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sex sex;

    @Column
    @Setter
    private String phoneNumber;

    @Column
    @Setter
    private String email;

    @Column
    @Setter
    private String address;

    @Column
    private String emergencyContactName;

    @Column
    private String emergencyContactPhone;

    @Column(nullable = false)
    private boolean consentGiven = false;

    @Column
    private Instant consentAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant registeredAt;

    @Setter
    @Column
    private Instant lastAccessedAt;

    protected Patient() {
    }

    public static Patient register(
            String mrn,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            Sex sex
    ) {
        Patient patient = new Patient();
        patient.mrn = mrn;
        patient.givenName = givenName;
        patient.familyName = familyName;
        patient.dateOfBirth = dateOfBirth;
        patient.sex = sex;
        patient.registeredAt = Instant.now();
        return patient;
    }

    public String getFullName() {
        if (middleName != null && !middleName.isBlank()) {
            return givenName + " " + middleName + " " + familyName;
        }
        return givenName + " " + familyName;
    }

    public void setEmergencyContact(String name, String phone) {
        this.emergencyContactName = name;
        this.emergencyContactPhone = phone;
    }

    public void recordConsent() {
        this.consentGiven = true;
        this.consentAt = Instant.now();
    }

    public void recordAccess() {
        this.lastAccessedAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
    }

    public enum Sex {
        MALE,
        FEMALE,
        OTHER,
        UNKNOWN
    }
}