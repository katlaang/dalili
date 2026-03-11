package dalili.com.base.domain.appointment.model;

import jakarta.persistence.*;
import lombok.Getter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Appointment lifecycle record with check-in window and queue linkage.
 */
@Entity
@Table(name = "appointments", indexes = {
        @Index(name = "idx_appointment_patient", columnList = "patientId"),
        @Index(name = "idx_appointment_status", columnList = "status"),
        @Index(name = "idx_appointment_scheduled", columnList = "scheduledAt"),
        @Index(name = "idx_appointment_window_close", columnList = "checkInWindowClosesAt"),
        @Index(name = "idx_appointment_number", columnList = "appointmentNumber")
})
@Getter
public class Appointment {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID patientId;

    @Column
    private UUID clinicianId;

    @Column(length = 255)
    private String clinicianName;

    @Column(length = 120)
    private String clinicianEmployeeId;

    @Column(length = 120)
    private String departmentCode;

    @Column(length = 255)
    private String departmentName;

    @Column(length = 80)
    private String facilityCode;

    @Column(length = 255)
    private String facilityName;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant scheduledAt;

    @Column(nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AppointmentStatus status;

    @Column(nullable = false)
    private Instant checkInWindowOpensAt;

    @Column(nullable = false)
    private Instant checkInWindowClosesAt;

    @Column
    private Instant checkedInAt;

    @Column
    private UUID queueTicketId;

    @Column(length = 40, unique = true)
    private String appointmentNumber;

    @Column
    private Integer appointmentNumberSequence;

    @Column
    private UUID triageAssessmentId;

    @Column
    private UUID encounterId;

    @Column
    private Instant deactivatedAt;

    @Column(length = 255)
    private String deactivationReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 120)
    private String createdBy;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, length = 120)
    private String updatedBy;

    @Column(length = 20)
    private String kioskAccessCode;

    @Column(length = 120)
    private String kioskQrToken;

    protected Appointment() {
    }

    public static Appointment schedule(
            UUID patientId,
            Instant scheduledAt,
            int durationMinutes,
            int checkInWindowMinutes,
            String createdBy
    ) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt is required");
        }
        if (durationMinutes < 5 || durationMinutes > 240) {
            throw new IllegalArgumentException("durationMinutes must be between 5 and 240");
        }
        if (checkInWindowMinutes < 5 || checkInWindowMinutes > 180) {
            throw new IllegalArgumentException("checkInWindowMinutes must be between 5 and 180");
        }
        String actor = required(createdBy, "createdBy", 120);

        Appointment appointment = new Appointment();
        appointment.patientId = patientId;
        appointment.scheduledAt = scheduledAt;
        appointment.durationMinutes = durationMinutes;
        appointment.checkInWindowOpensAt = scheduledAt.minusSeconds(checkInWindowMinutes * 60L);
        appointment.checkInWindowClosesAt = scheduledAt.plusSeconds(checkInWindowMinutes * 60L);
        appointment.status = AppointmentStatus.SCHEDULED;
        appointment.createdAt = Instant.now();
        appointment.createdBy = actor;
        appointment.updatedAt = appointment.createdAt;
        appointment.updatedBy = actor;
        appointment.kioskAccessCode = generateKioskAccessCode();
        appointment.kioskQrToken = UUID.randomUUID().toString();
        return appointment;
    }

    private static String required(String value, String field, int maxLen) {
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

    public void assignClinician(UUID clinicianId, String clinicianName, String clinicianEmployeeId) {
        this.clinicianId = clinicianId;
        this.clinicianName = normalize(clinicianName, 255);
        this.clinicianEmployeeId = normalize(clinicianEmployeeId, 120);
    }

    private static String generateKioskAccessCode() {
        int number = 100000 + SECURE_RANDOM.nextInt(900000);
        return String.valueOf(number);
    }

    public void setDepartment(String departmentCode, String departmentName) {
        this.departmentCode = normalize(departmentCode, 120);
        this.departmentName = normalize(departmentName, 255);
    }

    public void assignAppointmentNumber(String appointmentNumber, int appointmentNumberSequence) {
        String normalized = normalize(appointmentNumber, 40);
        if (normalized == null) {
            throw new IllegalArgumentException("appointmentNumber is required");
        }
        if (appointmentNumberSequence < 1) {
            throw new IllegalArgumentException("appointmentNumberSequence must be >= 1");
        }
        this.appointmentNumber = normalized;
        this.appointmentNumberSequence = appointmentNumberSequence;
    }

    public void setFacility(String facilityCode, String facilityName) {
        this.facilityCode = normalize(facilityCode, 80);
        this.facilityName = normalize(facilityName, 255);
    }

    public void checkIn(UUID queueTicketId, String actor) {
        if (this.status != AppointmentStatus.SCHEDULED) {
            throw new IllegalStateException("Appointment cannot be checked in from status " + this.status);
        }
        if (queueTicketId == null) {
            throw new IllegalArgumentException("queueTicketId is required");
        }
        this.queueTicketId = queueTicketId;
        this.checkedInAt = Instant.now();
        this.status = AppointmentStatus.CHECKED_IN;
        touch(actor);
    }

    public void markInTriage(UUID triageAssessmentId, String actor) {
        this.triageAssessmentId = triageAssessmentId;
        this.status = AppointmentStatus.IN_TRIAGE;
        touch(actor);
    }

    public void markInQueue(String actor) {
        this.status = AppointmentStatus.IN_QUEUE;
        touch(actor);
    }

    public void markInProgress(UUID encounterId, String actor) {
        this.encounterId = encounterId;
        this.status = AppointmentStatus.IN_PROGRESS;
        touch(actor);
    }

    public void markCompleted(String actor) {
        this.status = AppointmentStatus.COMPLETED;
        touch(actor);
    }

    public void deactivate(String reason, String actor) {
        if (this.status != AppointmentStatus.SCHEDULED) {
            return;
        }
        this.status = AppointmentStatus.DEACTIVATED;
        this.deactivatedAt = Instant.now();
        this.deactivationReason = normalize(reason, 255);
        touch(actor);
    }

    public void cancel(String reason, String actor) {
        if (this.status == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointment cannot be cancelled");
        }
        this.status = AppointmentStatus.CANCELLED;
        this.deactivatedAt = Instant.now();
        this.deactivationReason = normalize(reason, 255);
        touch(actor);
    }

    public boolean canCheckInAt(Instant now) {
        if (now == null) {
            return false;
        }
        return !now.isBefore(this.checkInWindowOpensAt) && !now.isAfter(this.checkInWindowClosesAt);
    }

    public void setReason(String reason) {
        this.reason = normalize(reason, 500);
    }

    public boolean missedCheckInWindow(Instant now) {
        return this.status == AppointmentStatus.SCHEDULED
                && now != null
                && now.isAfter(this.checkInWindowClosesAt);
    }

    public boolean matchesKioskCredential(String accessCode, String qrToken) {
        String normalizedCode = normalize(accessCode, 20);
        String normalizedQr = normalize(qrToken, 120);

        boolean codeMatch = normalizedCode != null
                && this.kioskAccessCode != null
                && this.kioskAccessCode.equalsIgnoreCase(normalizedCode);

        boolean qrMatch = normalizedQr != null
                && this.kioskQrToken != null
                && this.kioskQrToken.equals(normalizedQr);

        return codeMatch || qrMatch;
    }

    private void touch(String actor) {
        this.updatedAt = Instant.now();
        this.updatedBy = required(actor, "updatedBy", 120);
    }

    public enum AppointmentStatus {
        SCHEDULED,
        CHECKED_IN,
        IN_TRIAGE,
        IN_QUEUE,
        IN_PROGRESS,
        COMPLETED,
        DEACTIVATED,
        CANCELLED,
        RESCHEDULED
    }
}
