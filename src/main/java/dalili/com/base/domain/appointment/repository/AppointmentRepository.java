package dalili.com.base.domain.appointment.repository;

import dalili.com.base.domain.appointment.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    List<Appointment> findByPatientIdAndStatusInOrderByScheduledAtAsc(UUID patientId, List<Appointment.AppointmentStatus> statuses);

    List<Appointment> findByPatientIdOrderByScheduledAtDesc(UUID patientId);

    List<Appointment> findByClinicianIdAndStatusInOrderByScheduledAtAsc(
            UUID clinicianId,
            List<Appointment.AppointmentStatus> statuses
    );

    List<Appointment> findByClinicianEmployeeIdIgnoreCaseAndStatusInOrderByScheduledAtAsc(
            String clinicianEmployeeId,
            List<Appointment.AppointmentStatus> statuses
    );

    List<Appointment> findByStatusAndCheckInWindowClosesAtBefore(Appointment.AppointmentStatus status, Instant cutoff);

    List<Appointment> findByScheduledAtBetweenOrderByScheduledAtAsc(Instant fromInclusive, Instant toInclusive);

    Optional<Appointment> findByQueueTicketId(UUID queueTicketId);

    Optional<Appointment> findByAppointmentNumberIgnoreCase(String appointmentNumber);

    Optional<Appointment> findByKioskQrToken(String kioskQrToken);

    List<Appointment> findByAppointmentNumberIsNullOrderByCreatedAtAsc();

    @Query("SELECT MAX(a.appointmentNumberSequence) FROM Appointment a")
    Optional<Integer> findMaxAppointmentNumberSequence();
}
