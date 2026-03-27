package dalili.com.base.application.controller;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.*;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.model.MedicationOrder;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.encounter.repository.MedicationOrderRepository;
import dalili.com.base.domain.patient.model.*;
import dalili.com.base.domain.queue.QueueTicket;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import dalili.com.base.repository.queue.QueueTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Patient self-service portal endpoints.
 */
@RestController
@RequestMapping("/api/patient/portal")
public class PatientPortalController {

    private static final Logger log = LoggerFactory.getLogger(PatientPortalController.class);

    private final SessionContext sessionContext;
    private final AuditGuard auditGuard;
    private final AuditService auditService;
    private final PatientService patientService;
    private final QueueTicketRepository queueTicketRepository;
    private final QueueService queueService;
    private final EncounterRepository encounterRepository;
    private final MedicationOrderRepository medicationOrderRepository;
    private final PatientDataAccessService patientDataAccessService;
    private final PatientDataTransferService patientDataTransferService;
    private final PortalMessagingService portalMessagingService;
    private final PrescriptionRenewalService prescriptionRenewalService;
    private final PatientClinicalRecordsService clinicalRecordsService;
    private final MedicationSafetyService medicationSafetyService;
    private final PatientEmergencyDataService patientEmergencyDataService;
    private final FacilityWorkflowConfigService facilityWorkflowConfigService;

    public PatientPortalController(
            SessionContext sessionContext,
            AuditGuard auditGuard,
            AuditService auditService,
            PatientService patientService,
            QueueTicketRepository queueTicketRepository,
            QueueService queueService,
            EncounterRepository encounterRepository,
            MedicationOrderRepository medicationOrderRepository,
            PatientDataAccessService patientDataAccessService,
            PatientDataTransferService patientDataTransferService,
            PortalMessagingService portalMessagingService,
            PrescriptionRenewalService prescriptionRenewalService,
            PatientClinicalRecordsService clinicalRecordsService,
            MedicationSafetyService medicationSafetyService,
            PatientEmergencyDataService patientEmergencyDataService,
            FacilityWorkflowConfigService facilityWorkflowConfigService
    ) {
        this.sessionContext = sessionContext;
        this.auditGuard = auditGuard;
        this.auditService = auditService;
        this.patientService = patientService;
        this.queueTicketRepository = queueTicketRepository;
        this.queueService = queueService;
        this.encounterRepository = encounterRepository;
        this.medicationOrderRepository = medicationOrderRepository;
        this.patientDataAccessService = patientDataAccessService;
        this.patientDataTransferService = patientDataTransferService;
        this.portalMessagingService = portalMessagingService;
        this.prescriptionRenewalService = prescriptionRenewalService;
        this.clinicalRecordsService = clinicalRecordsService;
        this.medicationSafetyService = medicationSafetyService;
        this.patientEmergencyDataService = patientEmergencyDataService;
        this.facilityWorkflowConfigService = facilityWorkflowConfigService;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> getSnapshot() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            log.info("Patient portal snapshot requested patientId={}", patientId);

            var profile = PatientProfileView.from(patientService.findById(patientId));
            var queue = getQueueViews(patientId, 15);
            var encounters = getEncounterViews(patientId, 8);
            var medications = getMedicationViews(patientId, 12);
            var labs = clinicalRecordsService.getLabResults(patientId, 6).stream().map(LabResultView::from).toList();
            var referrals = clinicalRecordsService.getReferrals(patientId, 6).stream().map(ReferralView::from).toList();
            var notes = getEncounterNoteViews(patientId, 6);
            var messages = portalMessagingService.getPatientMessages(patientId).stream()
                    .limit(8)
                    .map(PortalMessageView::from)
                    .toList();
            var renewals = prescriptionRenewalService.getPatientRenewalRequests(patientId).stream()
                    .limit(8)
                    .map(RenewalView::from)
                    .toList();
            var transfers = patientDataTransferService.getPatientRequests(patientId).stream()
                    .limit(8)
                    .map(TransferRequestView::from)
                    .toList();
            var access = patientDataAccessService.resolveAccess(patientId);
            long unread = messages.stream().filter(message -> message.readAt() == null).count();

            auditPatientView(patientId, "SNAPSHOT", "Patient viewed portal snapshot");
            log.info("Patient portal snapshot prepared patientId={}", patientId);

            return ResponseEntity.ok(new PatientPortalSnapshot(
                    profile,
                    queue,
                    queue.stream().filter(QueueTicketView::active).findFirst().orElse(null),
                    encounters,
                    medications,
                    labs,
                    referrals,
                    notes,
                    messages,
                    renewals,
                    transfers,
                    access.scope().name(),
                    (int) unread
            ));
        } catch (RuntimeException e) {
            log.warn("Patient portal snapshot failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/records")
    public ResponseEntity<?> getAllRecords() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();

            List<MedicationOrder> medications = medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId)
                    .stream()
                    .limit(40)
                    .toList();
            String allergies = patientEmergencyDataService.buildEmergencyData(patientId).knownAllergies();
            Map<UUID, List<String>> warnings = medicationSafetyService.evaluateContraindications(medications, allergies);

            var payload = Map.ofEntries(
                    Map.entry("profile", PatientProfileView.from(patientService.findById(patientId))),
                    Map.entry("queue", getQueueViews(patientId, 40)),
                    Map.entry("encounters", getEncounterViews(patientId, 40)),
                    Map.entry("medications", medications.stream().map(m -> MedicationWithWarningsView.from(
                            m,
                            warnings.getOrDefault(m.getId(), List.of())
                    )).toList()),
                    Map.entry("labs", clinicalRecordsService.getLabResults(patientId, 80).stream().map(LabResultView::from).toList()),
                    Map.entry("referrals", clinicalRecordsService.getReferrals(patientId, 80).stream().map(ReferralView::from).toList()),
                    Map.entry("notes", getEncounterNoteViews(patientId, 80)),
                    Map.entry("messages", portalMessagingService.getPatientMessages(patientId).stream().map(PortalMessageView::from).toList()),
                    Map.entry("renewals", prescriptionRenewalService.getPatientRenewalRequests(patientId).stream().map(RenewalView::from).toList()),
                    Map.entry("transferRequests", patientDataTransferService.getPatientRequests(patientId).stream().map(TransferRequestView::from).toList()),
                    Map.entry("authorizations", patientDataAccessService.getAuthorizationHistory(patientId).stream().map(AuthorizationView::from).toList())
            );

            auditPatientView(patientId, "ALL_RECORDS", "Patient viewed full record bundle");
            return ResponseEntity.ok(payload);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var profile = PatientProfileView.from(patientService.findById(patientId));
            auditPatientView(patientId, "PROFILE", "Patient viewed profile");
            return ResponseEntity.ok(profile);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/profile/emergency-contact")
    public ResponseEntity<?> updateProfileEmergencyContact(@RequestBody UpdateEmergencyContactRequest request) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            Patient updated = patientService.updateEmergencyContact(
                    patientId,
                    request.name(),
                    request.phone()
            );
            auditPatientView(patientId, "PROFILE_UPDATE", "Patient updated emergency contact");
            return ResponseEntity.ok(PatientProfileView.from(updated));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/queue")
    public ResponseEntity<?> getQueue() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var queue = getQueueViews(patientId, 40);
            auditPatientView(patientId, "QUEUE", "Patient viewed queue");
            return ResponseEntity.ok(queue);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/checkin")
    public ResponseEntity<?> checkInFromPortal(@RequestBody PortalCheckInRequest request) {
        try {
            auditGuard.assertSessionActive();
            facilityWorkflowConfigService.assertPatientPortalEnabled();
            UUID patientId = requirePatientSession();
            log.info("Patient portal check-in requested patientId={} category={}", patientId, request.category());
            if (request.consentForDataAccess() != null && request.consentForDataAccess()) {
                patientDataAccessService.recordPatientConsent(patientId);
            }
            QueueTicket ticket = queueService.issueTicket(
                    patientId,
                    request.category(),
                    request.complaint()
            );
            auditService.record("PATIENT_PORTAL_CHECKIN", patientId,
                    "Patient self check-in via portal ticket=" + ticket.getTicketNumber());
            log.info("Patient portal check-in completed patientId={} ticketId={}", patientId, ticket.getId());
            return ResponseEntity.ok(QueueTicketView.from(ticket));
        } catch (RuntimeException e) {
            log.warn("Patient portal check-in failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/encounters")
    public ResponseEntity<?> getEncounters() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var encounters = getEncounterViews(patientId, 40);
            auditPatientView(patientId, "ENCOUNTERS", "Patient viewed encounters");
            return ResponseEntity.ok(encounters);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/medications")
    public ResponseEntity<?> getMedications() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var medications = getMedicationViews(patientId, 40);
            auditPatientView(patientId, "MEDICATIONS", "Patient viewed medications");
            return ResponseEntity.ok(medications);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/medications/contraindications")
    public ResponseEntity<?> getMedicationContraindications() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();

            List<MedicationOrder> medications = medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId)
                    .stream()
                    .limit(40)
                    .toList();
            String allergies = patientEmergencyDataService.buildEmergencyData(patientId).knownAllergies();
            Map<UUID, List<String>> warnings = medicationSafetyService.evaluateContraindications(medications, allergies);
            var response = new ContraindicationResponse(
                    allergies,
                    medications.stream().map(m -> MedicationWithWarningsView.from(
                            m,
                            warnings.getOrDefault(m.getId(), List.of())
                    )).toList()
            );

            auditPatientView(patientId, "MEDICATION_CONTRAINDICATIONS", "Patient viewed contraindication warnings");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/labs")
    public ResponseEntity<?> getLabs() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var labs = clinicalRecordsService.getLabResults(patientId, 80).stream().map(LabResultView::from).toList();
            auditPatientView(patientId, "LABS", "Patient viewed labs");
            return ResponseEntity.ok(labs);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/referrals")
    public ResponseEntity<?> getReferrals() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var referrals = clinicalRecordsService.getReferrals(patientId, 80).stream().map(ReferralView::from).toList();
            auditPatientView(patientId, "REFERRALS", "Patient viewed referrals");
            return ResponseEntity.ok(referrals);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/notes")
    public ResponseEntity<?> getEncounterNotes() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var notes = getEncounterNoteViews(patientId, 80);
            auditPatientView(patientId, "NOTES", "Patient viewed encounter notes");
            return ResponseEntity.ok(notes);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/messages")
    public ResponseEntity<?> getMessages() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var messages = portalMessagingService.getPatientMessages(patientId).stream().map(PortalMessageView::from).toList();
            auditPatientView(patientId, "MESSAGES", "Patient viewed messages");
            return ResponseEntity.ok(messages);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(@RequestBody SendPatientMessageRequest request) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            log.info("Patient portal message send requested patientId={} category={}", patientId, request.category());
            PortalMessage message = portalMessagingService.sendFromPatient(
                    patientId,
                    new PortalMessagingService.PatientMessageInput(
                            request.category(),
                            request.subject(),
                            request.body(),
                            request.targetStaffId(),
                            request.linkedEncounterId(),
                            request.linkedMedicationOrderId(),
                            request.linkedLabResultId(),
                            request.linkedReferralId()
                    )
            );
            log.info("Patient portal message sent patientId={} messageId={}", patientId, message.getId());
            return ResponseEntity.ok(PortalMessageView.from(message));
        } catch (RuntimeException e) {
            log.warn("Patient portal message send failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/messages/{messageId}/read")
    public ResponseEntity<?> markMessageRead(@PathVariable UUID messageId) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            PortalMessage message = portalMessagingService.markRead(messageId);
            if (!message.getPatientId().equals(patientId)) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Message does not belong to current patient"));
            }
            return ResponseEntity.ok(PortalMessageView.from(message));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/renewals")
    public ResponseEntity<?> getRenewalRequests() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var renewals = prescriptionRenewalService.getPatientRenewalRequests(patientId).stream().map(RenewalView::from).toList();
            auditPatientView(patientId, "RENEWALS", "Patient viewed prescription renewal requests");
            return ResponseEntity.ok(renewals);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/renewals/request")
    public ResponseEntity<?> requestRenewal(@RequestBody RenewalRequestInput request) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            log.info("Patient portal renewal request patientId={} medicationOrderId={}",
                    patientId, request.medicationOrderId());
            PrescriptionRenewalRequest renewal = prescriptionRenewalService.requestRenewal(
                    patientId,
                    request.medicationOrderId(),
                    request.note()
            );
            log.info("Patient portal renewal created patientId={} renewalId={}", patientId, renewal.getId());
            return ResponseEntity.ok(RenewalView.from(renewal));
        } catch (RuntimeException e) {
            log.warn("Patient portal renewal request failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/transfers")
    public ResponseEntity<?> getTransferRequests() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var requests = patientDataTransferService.getPatientRequests(patientId).stream().map(TransferRequestView::from).toList();
            auditPatientView(patientId, "TRANSFER_REQUESTS", "Patient viewed transfer requests");
            return ResponseEntity.ok(requests);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/transfers/request")
    public ResponseEntity<?> requestTransfer(@RequestBody TransferRequestInput request) {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            log.info("Patient transfer request submitted patientId={} targetFacility={}",
                    patientId, request.targetFacilityCode());
            PatientDataTransferRequest transferRequest = patientDataTransferService.requestTransfer(
                    patientId,
                    new PatientDataTransferService.RequestInput(
                            request.sourceFacilityCode(),
                            request.targetFacilityCode(),
                            request.reason(),
                            request.destinationUsesDalili()
                    )
            );
            log.info("Patient transfer request created patientId={} requestId={}", patientId, transferRequest.getId());
            return ResponseEntity.ok(TransferRequestView.from(transferRequest));
        } catch (RuntimeException e) {
            log.warn("Patient transfer request failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/consent")
    public ResponseEntity<?> recordConsent() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            log.info("Patient consent request via portal patientId={}", patientId);
            var authorization = patientDataAccessService.recordPatientConsent(patientId);
            log.info("Patient consent recorded via portal patientId={} authorizationId={}",
                    patientId, authorization.getId());
            return ResponseEntity.ok(AuthorizationView.from(authorization));
        } catch (RuntimeException e) {
            log.warn("Patient consent request failed reason={}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/authorizations")
    public ResponseEntity<?> getAuthorizations() {
        try {
            auditGuard.assertSessionActive();
            UUID patientId = requirePatientSession();
            var authorizations = patientDataAccessService.getAuthorizationHistory(patientId).stream()
                    .map(AuthorizationView::from)
                    .toList();
            auditPatientView(patientId, "AUTHORIZATIONS", "Patient viewed authorization history");
            return ResponseEntity.ok(authorizations);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    private List<QueueTicketView> getQueueViews(UUID patientId, int limit) {
        LocalDate today = LocalDate.now();
        LocalDate lookback = today.minusDays(90);
        return queueTicketRepository.findByPatientIdAndQueueDateBetweenOrderByQueueDateDesc(
                        patientId,
                        lookback,
                        today
                ).stream()
                .limit(limit)
                .map(QueueTicketView::from)
                .toList();
    }

    private List<EncounterView> getEncounterViews(UUID patientId, int limit) {
        return encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .limit(limit)
                .map(EncounterView::from)
                .toList();
    }

    private List<MedicationOrderView> getMedicationViews(UUID patientId, int limit) {
        return medicationOrderRepository.findByPatientIdOrderByOrderedAtDesc(patientId).stream()
                .limit(limit)
                .map(MedicationOrderView::from)
                .toList();
    }

    private List<EncounterNoteView> getEncounterNoteViews(UUID patientId, int limit) {
        return encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .limit(limit)
                .map(EncounterNoteView::from)
                .toList();
    }

    private UUID requirePatientSession() {
        UUID patientId = sessionContext.patientId();
        if (patientId == null) {
            throw new IllegalStateException("No patient linked to current session");
        }
        return patientId;
    }

    private void auditPatientView(UUID patientId, String resource, String details) {
        auditService.record("PATIENT_PORTAL_DATA_VIEW", patientId, "resource=" + resource + " details=" + details);
    }

    public record ErrorResponse(String error) {
    }

    public record PatientPortalSnapshot(
            PatientProfileView profile,
            List<QueueTicketView> queue,
            QueueTicketView activeQueueTicket,
            List<EncounterView> recentEncounters,
            List<MedicationOrderView> recentMedications,
            List<LabResultView> recentLabs,
            List<ReferralView> recentReferrals,
            List<EncounterNoteView> recentNotes,
            List<PortalMessageView> recentMessages,
            List<RenewalView> renewalRequests,
            List<TransferRequestView> transferRequests,
            String accessScope,
            int unreadMessageCount
    ) {
    }

    public record ContraindicationResponse(
            String knownAllergies,
            List<MedicationWithWarningsView> medications
    ) {
    }

    public record PatientProfileView(
            UUID id,
            String mrn,
            String fullName,
            LocalDate dateOfBirth,
            String sex,
            Integer ageYears,
            String phoneNumber,
            String email,
            String address,
            String emergencyContactName,
            String emergencyContactPhone
    ) {
        static PatientProfileView from(Patient patient) {
            Integer age = null;
            if (patient.getDateOfBirth() != null) {
                age = Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears();
            }
            return new PatientProfileView(
                    patient.getId(),
                    patient.getMrn(),
                    patient.getFullName(),
                    patient.getDateOfBirth(),
                    patient.getSex() != null ? patient.getSex().name() : null,
                    age,
                    patient.getPhoneNumber(),
                    patient.getEmail(),
                    patient.getAddress(),
                    patient.getEmergencyContactName(),
                    patient.getEmergencyContactPhone()
            );
        }
    }

    public record QueueTicketView(
            UUID id,
            String ticketNumber,
            String trackingNumber,
            String category,
            String triageLevel,
            String status,
            String initialComplaint,
            String counterNumber,
            boolean active,
            long waitTimeMinutes,
            int targetWaitMinutes,
            String queueDate,
            String createdAt,
            String calledAt,
            String startedAt,
            String completedAt,
            String triageSummary,
            String suggestedPrimaryDiagnosis,
            String admissionReason,
            UUID appointmentId,
            String appointmentScheduledAt,
            String appointmentWindowOpensAt,
            String appointmentWindowClosesAt,
            boolean appointmentPriorityBoostApplied
    ) {
        static QueueTicketView from(QueueTicket ticket) {
            boolean active = ticket.getStatus() == QueueTicket.QueueStatus.WAITING
                    || ticket.getStatus() == QueueTicket.QueueStatus.CALLED
                    || ticket.getStatus() == QueueTicket.QueueStatus.IN_PROGRESS;
            return new QueueTicketView(
                    ticket.getId(),
                    ticket.getTicketNumber(),
                    ticket.getTrackingNumber(),
                    ticket.getCategory().name(),
                    ticket.getTriageLevel().name(),
                    ticket.getStatus().name(),
                    ticket.getInitialComplaint(),
                    ticket.getCounterNumber(),
                    active,
                    ticket.getWaitTimeMinutes(),
                    ticket.getTargetWaitMinutes(),
                    ticket.getQueueDate() != null ? ticket.getQueueDate().toString() : null,
                    ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : null,
                    ticket.getCalledAt() != null ? ticket.getCalledAt().toString() : null,
                    ticket.getStartedAt() != null ? ticket.getStartedAt().toString() : null,
                    ticket.getCompletedAt() != null ? ticket.getCompletedAt().toString() : null,
                    ticket.getTriageSummary(),
                    ticket.getSuggestedPrimaryDiagnosis(),
                    ticket.getAdmissionReason(),
                    ticket.getAppointmentId(),
                    ticket.getAppointmentScheduledAt() != null ? ticket.getAppointmentScheduledAt().toString() : null,
                    ticket.getAppointmentWindowOpensAt() != null ? ticket.getAppointmentWindowOpensAt().toString() : null,
                    ticket.getAppointmentWindowClosesAt() != null ? ticket.getAppointmentWindowClosesAt().toString() : null,
                    ticket.isAppointmentPriorityBoostApplied()
            );
        }
    }

    public record EncounterView(
            UUID id,
            String encounterType,
            String status,
            String chiefComplaint,
            String clinicianName,
            int diagnosisCount,
            int medicationCount,
            boolean noteConfirmed,
            String startedAt,
            String completedAt
    ) {
        static EncounterView from(Encounter encounter) {
            return new EncounterView(
                    encounter.getId(),
                    encounter.getEncounterType().name(),
                    encounter.getStatus().name(),
                    encounter.getChiefComplaint(),
                    encounter.getClinicianName(),
                    encounter.getDiagnoses().size(),
                    encounter.getMedicationOrders().size(),
                    encounter.isNoteConfirmed(),
                    encounter.getStartedAt() != null ? encounter.getStartedAt().toString() : null,
                    encounter.getCompletedAt() != null ? encounter.getCompletedAt().toString() : null
            );
        }
    }

    public record MedicationOrderView(
            UUID id,
            String medicationName,
            String brandName,
            String dosage,
            String dosageForm,
            String frequency,
            String route,
            int durationDays,
            int quantity,
            String instructions,
            String indication,
            String status,
            String orderedByName,
            String orderedAt,
            String printedAt,
            String dispensedAt
    ) {
        static MedicationOrderView from(MedicationOrder order) {
            return new MedicationOrderView(
                    order.getId(),
                    order.getMedicationName(),
                    order.getBrandName(),
                    order.getDosage(),
                    order.getDosageForm() != null ? order.getDosageForm().name() : null,
                    order.getFrequency(),
                    order.getRoute() != null ? order.getRoute().name() : null,
                    order.getDurationDays(),
                    order.getQuantity(),
                    order.getInstructions(),
                    order.getIndication(),
                    order.getStatus() != null ? order.getStatus().name() : null,
                    order.getOrderedByName(),
                    order.getOrderedAt() != null ? order.getOrderedAt().toString() : null,
                    order.getPrintedAt() != null ? order.getPrintedAt().toString() : null,
                    order.getDispensedAt() != null ? order.getDispensedAt().toString() : null
            );
        }
    }

    public record MedicationWithWarningsView(
            UUID id,
            String medicationName,
            String dosage,
            String frequency,
            String status,
            List<String> contraindicationWarnings
    ) {
        static MedicationWithWarningsView from(MedicationOrder order, List<String> warnings) {
            return new MedicationWithWarningsView(
                    order.getId(),
                    order.getMedicationName(),
                    order.getDosage(),
                    order.getFrequency(),
                    order.getStatus() != null ? order.getStatus().name() : null,
                    warnings
            );
        }
    }

    public record LabResultView(
            UUID id,
            UUID encounterId,
            String testName,
            String resultValue,
            String unit,
            String referenceRange,
            String interpretation,
            boolean criticalResult,
            String recordedAt,
            String recordedByName
    ) {
        static LabResultView from(LabResultRecord result) {
            return new LabResultView(
                    result.getId(),
                    result.getEncounterId(),
                    result.getTestName(),
                    result.getResultValue(),
                    result.getUnit(),
                    result.getReferenceRange(),
                    result.getInterpretation(),
                    result.isCriticalResult(),
                    result.getRecordedAt() != null ? result.getRecordedAt().toString() : null,
                    result.getRecordedByName()
            );
        }
    }

    public record ReferralView(
            UUID id,
            UUID encounterId,
            String referredToFacility,
            String specialty,
            String reason,
            String status,
            String referredAt,
            String referredByName,
            String notes,
            String outputFormat,
            Boolean destinationUsesDalili,
            String printedAt
    ) {
        static ReferralView from(ReferralRecord referral) {
            return new ReferralView(
                    referral.getId(),
                    referral.getEncounterId(),
                    referral.getReferredToFacility(),
                    referral.getSpecialty(),
                    referral.getReason(),
                    referral.getStatus() != null ? referral.getStatus().name() : null,
                    referral.getReferredAt() != null ? referral.getReferredAt().toString() : null,
                    referral.getReferredByName(),
                    referral.getNotes(),
                    referral.getOutputFormat() != null ? referral.getOutputFormat().name() : null,
                    referral.getDestinationUsesDalili(),
                    referral.getPrintedAt() != null ? referral.getPrintedAt().toString() : null
            );
        }
    }

    public record EncounterNoteView(
            UUID encounterId,
            String encounterType,
            String status,
            String clinicianName,
            String physicianAuthoredNote,
            String finalNote,
            String aiDiscrepancySummary,
            Integer transcriptAccuracyScore,
            boolean noteConfirmed,
            String startedAt,
            String completedAt
    ) {
        static EncounterNoteView from(Encounter encounter) {
            return new EncounterNoteView(
                    encounter.getId(),
                    encounter.getEncounterType() != null ? encounter.getEncounterType().name() : null,
                    encounter.getStatus() != null ? encounter.getStatus().name() : null,
                    encounter.getClinicianName(),
                    encounter.getPhysicianAuthoredNote(),
                    encounter.getFinalNote(),
                    encounter.getTranscriptDiscrepancySummary(),
                    encounter.getTranscriptAccuracyScore(),
                    encounter.isNoteConfirmed(),
                    encounter.getStartedAt() != null ? encounter.getStartedAt().toString() : null,
                    encounter.getCompletedAt() != null ? encounter.getCompletedAt().toString() : null
            );
        }
    }

    public record PortalMessageView(
            UUID id,
            String direction,
            String category,
            String senderName,
            String recipientName,
            String subject,
            String body,
            String createdAt,
            String readAt
    ) {
        static PortalMessageView from(PortalMessage message) {
            return new PortalMessageView(
                    message.getId(),
                    message.getDirection() != null ? message.getDirection().name() : null,
                    message.getCategory() != null ? message.getCategory().name() : null,
                    message.getSenderName(),
                    message.getRecipientName(),
                    message.getSubject(),
                    message.getBody(),
                    message.getCreatedAt() != null ? message.getCreatedAt().toString() : null,
                    message.getReadAt() != null ? message.getReadAt().toString() : null
            );
        }
    }

    public record RenewalView(
            UUID id,
            UUID medicationOrderId,
            String medicationName,
            String dosage,
            String frequency,
            String status,
            String requestNote,
            String requestedAt,
            String reviewedAt,
            String reviewComments
    ) {
        static RenewalView from(PrescriptionRenewalRequest request) {
            return new RenewalView(
                    request.getId(),
                    request.getMedicationOrderId(),
                    request.getMedicationName(),
                    request.getDosage(),
                    request.getFrequency(),
                    request.getStatus() != null ? request.getStatus().name() : null,
                    request.getRequestNote(),
                    request.getRequestedAt() != null ? request.getRequestedAt().toString() : null,
                    request.getReviewedAt() != null ? request.getReviewedAt().toString() : null,
                    request.getReviewComments()
            );
        }
    }

    public record TransferRequestView(
            UUID id,
            String sourceFacilityCode,
            String targetFacilityCode,
            String reason,
            String status,
            boolean emergencyBlocked,
            Boolean destinationUsesDalili,
            UUID linkedReferralId,
            String requestedAt,
            String reviewedAt,
            String reviewNotes
    ) {
        static TransferRequestView from(PatientDataTransferRequest request) {
            return new TransferRequestView(
                    request.getId(),
                    request.getSourceFacilityCode(),
                    request.getTargetFacilityCode(),
                    request.getReason(),
                    request.getStatus() != null ? request.getStatus().name() : null,
                    request.isEmergencyBlocked(),
                    request.getDestinationUsesDalili(),
                    request.getLinkedReferralId(),
                    request.getRequestedAt() != null ? request.getRequestedAt().toString() : null,
                    request.getReviewedAt() != null ? request.getReviewedAt().toString() : null,
                    request.getReviewNotes()
            );
        }
    }

    public record AuthorizationView(
            UUID id,
            String authorizationType,
            String dataAccessScope,
            String authorizerRole,
            String authorizerName,
            String grantedAt,
            String expiresAt,
            boolean revoked
    ) {
        static AuthorizationView from(PatientDataAuthorization authorization) {
            return new AuthorizationView(
                    authorization.getId(),
                    authorization.getAuthorizationType() != null ? authorization.getAuthorizationType().name() : null,
                    authorization.getDataAccessScope() != null ? authorization.getDataAccessScope().name() : null,
                    authorization.getAuthorizerRole(),
                    authorization.getAuthorizerName(),
                    authorization.getGrantedAt() != null ? authorization.getGrantedAt().toString() : null,
                    authorization.getExpiresAt() != null ? authorization.getExpiresAt().toString() : null,
                    authorization.isRevoked()
            );
        }
    }

    public record SendPatientMessageRequest(
            PortalMessage.MessageCategory category,
            String subject,
            String body,
            String targetStaffId,
            UUID linkedEncounterId,
            UUID linkedMedicationOrderId,
            UUID linkedLabResultId,
            UUID linkedReferralId
    ) {
    }

    public record RenewalRequestInput(
            UUID medicationOrderId,
            String note
    ) {
    }

    public record TransferRequestInput(
            String sourceFacilityCode,
            String targetFacilityCode,
            String reason,
            Boolean destinationUsesDalili
    ) {
    }

    public record PortalCheckInRequest(
            QueueTicket.QueueCategory category,
            String complaint,
            Boolean consentForDataAccess
    ) {
    }

    public record UpdateEmergencyContactRequest(
            String name,
            String phone
    ) {
    }
}
