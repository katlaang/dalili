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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final PatientService patientService;
    private final FacilityWorkflowConfigService facilityConfigService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            QueueService queueService,
            QueueTicketRepository queueTicketRepository,
            PatientService patientService,
            FacilityWorkflowConfigService facilityConfigService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.appointmentRepository = appointmentRepository;
        this.queueService = queueService;
        this.queueTicketRepository = queueTicketRepository;
        this.patientService = patientService;
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
        var workflowConfig = facilityConfigService.getCurrentConfig();
        int windowMinutes = workflowConfig.getAppointmentCheckInWindowMinutes();
        Appointment appointment = Appointment.schedule(
                input.patientId(),
                input.scheduledAt(),
                input.durationMinutes() == null ? 20 : input.durationMinutes(),
                windowMinutes,
                actor
        );
        int sequence = appointmentRepository.findMaxAppointmentNumberSequence()
                .map(previous -> previous + 1)
                .orElse(1);
        appointment.assignAppointmentNumber(String.format("PR-%03d", sequence), sequence);
        appointment.assignClinician(input.clinicianId(), input.clinicianName(), input.clinicianEmployeeId());
        appointment.setDepartment(input.departmentCode(), input.departmentName());
        appointment.setFacility(workflowConfig.getFacilityCode(), workflowConfig.getFacilityName());
        appointment.setReason(input.reason());

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
        return appointmentRepository.findByPatientIdAndStatusInOrderByScheduledAtAsc(patientId, pendingStatuses());
    }

    public List<Appointment> getPatientAppointmentHistory(UUID patientId) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();
        return appointmentRepository.findByPatientIdOrderByScheduledAtDesc(patientId);
    }

    public List<Appointment> getAssignedPendingAppointmentsForCurrentClinician() {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();

        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.NURSE && role != Role.ADMIN && role != Role.SUPER_ADMIN) {
            throw new AppointmentException("Current role cannot view assigned appointments");
        }

        List<Appointment> results = new ArrayList<>();
        UUID clinicianUserId = sessionContext.userId();
        if (clinicianUserId != null) {
            results.addAll(appointmentRepository.findByClinicianIdAndStatusInOrderByScheduledAtAsc(
                    clinicianUserId,
                    pendingStatuses()
            ));
        }

        String clinicianEmployeeId = sessionContext.username();
        if (!isBlank(clinicianEmployeeId)) {
            results.addAll(appointmentRepository.findByClinicianEmployeeIdIgnoreCaseAndStatusInOrderByScheduledAtAsc(
                    clinicianEmployeeId,
                    pendingStatuses()
            ));
        }

        LinkedHashMap<UUID, Appointment> unique = new LinkedHashMap<>();
        for (Appointment appointment : results) {
            unique.putIfAbsent(appointment.getId(), appointment);
        }
        return new ArrayList<>(unique.values());
    }

    public List<Appointment> getKioskPendingAppointments(UUID patientId, String accessCode, String qrToken) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();

        if (patientId == null) {
            throw new AppointmentException("Patient ID is required");
        }
        if (isBlank(accessCode) && isBlank(qrToken)) {
            throw new AppointmentException("Access code or QR token is required");
        }

        List<Appointment> pending = appointmentRepository.findByPatientIdAndStatusInOrderByScheduledAtAsc(
                patientId,
                List.of(Appointment.AppointmentStatus.SCHEDULED)
        );

        return pending.stream()
                .filter(appointment -> appointment.matchesKioskCredential(accessCode, qrToken))
                .toList();
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
        facilityConfigService.assertAppointmentFlowEnabled();

        if (patientId == null) {
            throw new AppointmentException("Patient ID is required");
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentException("Appointment not found"));
        assertAppointmentForCurrentFacility(appointment);

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
    public AppointmentCheckInResult checkInAppointmentFromKiosk(
            UUID patientId,
            UUID appointmentId,
            String accessCode,
            String qrToken,
            String complaint
    ) {
        auditGuard.assertSessionActive();
        facilityConfigService.assertAppointmentFlowEnabled();

        if (patientId == null) {
            throw new AppointmentException("Patient ID is required");
        }
        if (appointmentId == null) {
            throw new AppointmentException("Appointment ID is required");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new AppointmentException("Appointment not found"));

        if (!patientId.equals(appointment.getPatientId())) {
            throw new AppointmentException("Appointment does not belong to provided patient");
        }
        if (!appointment.matchesKioskCredential(accessCode, qrToken)) {
            throw new AppointmentException("Invalid appointment access credential");
        }

        return checkInAppointment(patientId, appointmentId, complaint);
    }

    @Transactional
    public AppointmentCheckInResult checkInAppointmentByNumberFromKiosk(
            String appointmentNumber,
            String givenName,
            String familyName,
            java.time.LocalDate dateOfBirth,
            String complaint
    ) {
        facilityConfigService.assertAppointmentFlowEnabled();

        if (isBlank(appointmentNumber)) {
            throw new AppointmentException("Appointment number is required");
        }
        if (isBlank(givenName) || isBlank(familyName) || dateOfBirth == null) {
            throw new AppointmentException("Name and date of birth are required");
        }

        Appointment appointment = appointmentRepository.findByAppointmentNumberIgnoreCase(appointmentNumber.trim())
                .orElseThrow(() -> new AppointmentException("Appointment not found"));
        assertAppointmentForCurrentFacility(appointment);
        var patient = patientService.findById(appointment.getPatientId());
        if (!patient.getGivenName().equalsIgnoreCase(givenName.trim())
                || !patient.getFamilyName().equalsIgnoreCase(familyName.trim())
                || !patient.getDateOfBirth().equals(dateOfBirth)) {
            throw new AppointmentException("Appointment details do not match patient demographics");
        }

        return checkInAppointment(appointment.getPatientId(), appointment.getId(), complaint);
    }

    @Transactional
    public AppointmentCheckInResult checkInAppointmentByQrFromKiosk(
            String qrToken,
            String givenName,
            String familyName,
            java.time.LocalDate dateOfBirth,
            String complaint
    ) {
        facilityConfigService.assertAppointmentFlowEnabled();

        if (isBlank(qrToken)) {
            throw new AppointmentException("QR token is required");
        }
        if (isBlank(givenName) || isBlank(familyName) || dateOfBirth == null) {
            throw new AppointmentException("Name and date of birth are required");
        }

        Appointment appointment = appointmentRepository.findByKioskQrToken(qrToken.trim())
                .orElseThrow(() -> new AppointmentException("Appointment not found"));
        assertAppointmentForCurrentFacility(appointment);

        var patient = patientService.findById(appointment.getPatientId());
        if (!patient.getGivenName().equalsIgnoreCase(givenName.trim())
                || !patient.getFamilyName().equalsIgnoreCase(familyName.trim())
                || !patient.getDateOfBirth().equals(dateOfBirth)) {
            throw new AppointmentException("Appointment details do not match patient demographics");
        }

        return checkInAppointment(appointment.getPatientId(), appointment.getId(), complaint);
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

    private List<Appointment.AppointmentStatus> pendingStatuses() {
        List<Appointment.AppointmentStatus> statuses = new ArrayList<>();
        statuses.add(Appointment.AppointmentStatus.SCHEDULED);
        statuses.add(Appointment.AppointmentStatus.CHECKED_IN);
        statuses.add(Appointment.AppointmentStatus.IN_TRIAGE);
        statuses.add(Appointment.AppointmentStatus.IN_QUEUE);
        statuses.add(Appointment.AppointmentStatus.IN_PROGRESS);
        return statuses;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void assertAppointmentForCurrentFacility(Appointment appointment) {
        String appointmentFacilityCode = appointment.getFacilityCode();
        if (isBlank(appointmentFacilityCode)) {
            return;
        }
        String currentFacilityCode = facilityConfigService.getCurrentConfig().getFacilityCode();
        if (isBlank(currentFacilityCode)) {
            return;
        }
        if (!appointmentFacilityCode.equalsIgnoreCase(currentFacilityCode)) {
            throw new AppointmentException("Appointment is not for this facility");
        }
    }

    private void assertCanManageAppointments() {
        Role role = sessionContext.role();
        if (role != Role.ADMIN && role != Role.SUPER_ADMIN && role != Role.RECEPTIONIST && role != Role.PHYSICIAN && role != Role.NURSE) {
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
            String departmentName,
            String reason
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
