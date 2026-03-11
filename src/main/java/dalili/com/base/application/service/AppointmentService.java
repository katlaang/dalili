package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.appointment.model.Appointment;
import dalili.com.base.domain.appointment.repository.AppointmentRepository;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Appointment scheduling and check-in lifecycle service.
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final QueueService queueService;
    private final QueueTicketRepository queueTicketRepository;
    private final FacilityWorkflowConfigService facilityConfigService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            QueueService queueService,
            QueueTicketRepository queueTicketRepository,
            FacilityWorkflowConfigService facilityConfigService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.appointmentRepository = appointmentRepository;
        this.queueService = queueService;
        this.queueTicketRepository = queueTicketRepository;
        this.facilityConfigService = facilityConfigService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public Appointment scheduleAppointment(ScheduleInput input) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();
        assertCanManageAppointments();

        if (input == null) {
            throw new AppointmentException("Appointment payload is required");
        }

        String actor = actor();
        int windowMinutes = facilityConfigService.getAppointmentCheckInWindowMinutes();
        Appointment appointment = Appointment.schedule(
                input.patientId(),
                input.scheduledAt(),
                input.durationMinutes() == null ? 20 : input.durationMinutes(),
                windowMinutes,
                actor
        );
        appointment.assignClinician(input.clinicianId(), input.clinicianName(), input.clinicianEmployeeId());
        appointment.setDepartment(input.departmentCode(), input.departmentName());

        appointment = appointmentRepository.save(appointment);
        log.info("Appointment scheduled appointmentId={} patientId={} scheduledAt={}",
                appointment.getId(), appointment.getPatientId(), appointment.getScheduledAt());

        auditService.record(
                "APPOINTMENT_SCHEDULED",
                appointment.getPatientId(),
                "appointmentId=" + appointment.getId() + ", scheduledAt=" + appointment.getScheduledAt()
        );

        return appointment;
    }

    public List<Appointment> getPatientPendingAppointments(UUID patientId) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();
        return appointmentRepository.findByPatientIdAndStatusInOrderByScheduledAtAsc(
                patientId,
                List.of(
                        Appointment.AppointmentStatus.SCHEDULED,
                        Appointment.AppointmentStatus.CHECKED_IN,
                        Appointment.AppointmentStatus.IN_TRIAGE,
                        Appointment.AppointmentStatus.IN_QUEUE,
                        Appointment.AppointmentStatus.IN_PROGRESS
                )
        );
    }

    public List<Appointment> getPatientAppointmentHistory(UUID patientId) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();
        return appointmentRepository.findByPatientIdOrderByScheduledAtDesc(patientId);
    }

    public List<Appointment> getTodayAppointments() {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();

        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).minusNanos(1).toInstant();
        return appointmentRepository.findByScheduledAtBetweenOrderByScheduledAtAsc(from, to);
    }

    @Transactional
    public AppointmentCheckInResult checkInAppointment(UUID patientId, UUID appointmentId, String complaint) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();

        if (patientId == null) {
            throw new AppointmentException("Patient ID is required");
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentException("Appointment not found"));

        if (!patientId.equals(appointment.getPatientId())) {
            throw new AppointmentException("Appointment does not belong to provided patient");
        }
        if (appointment.getStatus() != Appointment.AppointmentStatus.SCHEDULED) {
            throw new AppointmentException("Appointment is not pending check-in");
        }
        if (!appointment.canCheckInAt(Instant.now())) {
            throw new AppointmentException(String.format(
                    "Check-in window closed. Window was %s to %s",
                    appointment.getCheckInWindowOpensAt(),
                    appointment.getCheckInWindowClosesAt()
            ));
        }

        QueueTicket ticket = queueService.issueTicket(
                patientId,
                QueueTicket.QueueCategory.FOLLOW_UP,
                complaint
        );

        if (ticket.getAppointmentId() != null && !ticket.getAppointmentId().equals(appointmentId)) {
            throw new AppointmentException("Patient already has another appointment-linked queue ticket");
        }

        ticket.linkToAppointment(
                appointment.getId(),
                appointment.getScheduledAt(),
                appointment.getCheckInWindowOpensAt(),
                appointment.getCheckInWindowClosesAt()
        );
        ticket = queueTicketRepository.save(ticket);

        appointment.checkIn(ticket.getId(), actor());
        appointment = appointmentRepository.save(appointment);
        log.info("Appointment checked in appointmentId={} patientId={} queueTicketId={}",
                appointment.getId(), patientId, ticket.getId());

        auditService.record(
                "APPOINTMENT_CHECKED_IN",
                patientId,
                "appointmentId=" + appointmentId + ", ticket=" + ticket.getTicketNumber()
        );

        return new AppointmentCheckInResult(appointment, ticket);
    }

    @Transactional
    public int deactivateExpiredAppointments() {
        if (!facilityConfigService.isAppointmentFlowEnabled()) {
            return 0;
        }

        List<Appointment> expired = appointmentRepository.findByStatusAndCheckInWindowClosesAtBefore(
                Appointment.AppointmentStatus.SCHEDULED,
                Instant.now()
        );

        if (expired.isEmpty()) {
            return 0;
        }

        for (Appointment appointment : expired) {
            appointment.deactivate("MISSED_CHECK_IN_WINDOW", "SYSTEM");
        }
        appointmentRepository.saveAll(expired);
        log.info("Appointments auto-deactivated count={}", expired.size());

        auditService.record(
                "APPOINTMENT_AUTO_DEACTIVATE_BATCH",
                null,
                "count=" + expired.size()
        );
        return expired.size();
    }

    @Transactional
    public Appointment cancelAppointment(UUID appointmentId, String reason) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();
        assertCanManageAppointments();

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentException("Appointment not found"));
        appointment.cancel(reason, actor());
        appointment = appointmentRepository.save(appointment);
        log.info("Appointment cancelled appointmentId={} patientId={}",
                appointment.getId(), appointment.getPatientId());

        auditService.record(
                "APPOINTMENT_CANCELLED",
                appointment.getPatientId(),
                "appointmentId=" + appointment.getId() + ", reason=" + reason
        );

        return appointment;
    }

    private void assertCanManageAppointments() {
        Role role = sessionContext.role();
        if (role != Role.ADMIN && role != Role.RECEPTIONIST && role != Role.PHYSICIAN && role != Role.NURSE) {
            throw new AppointmentException("Current role cannot manage appointments");
        }
    }

    private String actor() {
        return sessionContext.username() != null ? sessionContext.username() : "SYSTEM";
    }

    public record ScheduleInput(
            UUID patientId,
            Instant scheduledAt,
            Integer durationMinutes,
            UUID clinicianId,
            String clinicianName,
            String clinicianEmployeeId,
            String departmentCode,
            String departmentName
    ) {
    }

    public record AppointmentCheckInResult(Appointment appointment, QueueTicket queueTicket) {
    }

    public static class AppointmentException extends RuntimeException {
        public AppointmentException(String message) {
            super(message);
        }
    }
}
