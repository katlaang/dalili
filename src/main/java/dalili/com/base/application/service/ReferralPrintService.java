package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.encounter.model.Encounter;
import dalili.com.base.domain.encounter.repository.EncounterRepository;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.patient.model.ReferralRecord;
import dalili.com.base.domain.patient.repository.ReferralRecordRepository;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Generates printable referral documents and records print events.
 */
@Service
public class ReferralPrintService {

    private static final Logger log = LoggerFactory.getLogger(ReferralPrintService.class);

    private final ReferralRecordRepository referralRepository;
    private final EncounterRepository encounterRepository;
    private final PatientService patientService;
    private final PatientEmergencyDataService emergencyDataService;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;
    private final String clinicName;

    public ReferralPrintService(
            ReferralRecordRepository referralRepository,
            EncounterRepository encounterRepository,
            PatientService patientService,
            PatientEmergencyDataService emergencyDataService,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext,
            @Value("${dalili.clinic.name:Dalili Health Clinic}") String clinicName
    ) {
        this.referralRepository = referralRepository;
        this.encounterRepository = encounterRepository;
        this.patientService = patientService;
        this.emergencyDataService = emergencyDataService;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
        this.clinicName = clinicName;
    }

    public PrintableReferral buildPrintableReferral(UUID referralId) {
        auditGuard.assertSessionActive();
        assertClinicalRole();

        ReferralRecord referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ReferralPrintException("Referral not found"));
        Patient patient = patientService.findById(referral.getPatientId());
        PatientEmergencyDataService.EmergencyDataView emergencyData = emergencyDataService.buildEmergencyData(patient.getId());
        Encounter encounter = referral.getEncounterId() != null
                ? encounterRepository.findById(referral.getEncounterId()).orElse(null)
                : null;

        List<String> diagnosisLines = emergencyData.diagnoses().stream()
                .limit(12)
                .map(diagnosis -> {
                    String date = diagnosis.diagnosedDate() != null ? diagnosis.diagnosedDate().toString() : "Unknown date";
                    return diagnosis.icdCode() + " - " + diagnosis.diagnosisName() + " (" + date + ")";
                })
                .toList();

        List<String> medicationLines = emergencyData.currentMedications().stream()
                .limit(20)
                .map(medication -> medication.medicationName()
                        + " | " + medication.dosage()
                        + " | " + medication.frequency())
                .toList();

        List<String> vitalsLines = emergencyData.previousVitals().stream()
                .limit(10)
                .map(vitals -> {
                    String bp = vitals.bloodPressureSystolic() != null && vitals.bloodPressureDiastolic() != null
                            ? vitals.bloodPressureSystolic() + "/" + vitals.bloodPressureDiastolic()
                            : "N/A";
                    return (vitals.assessedDate() != null ? vitals.assessedDate().toString() : "Unknown")
                            + " | BP " + bp
                            + " | HR " + (vitals.heartRateBpm() != null ? vitals.heartRateBpm() : "N/A")
                            + " | SpO2 " + (vitals.oxygenSaturation() != null ? vitals.oxygenSaturation() + "%" : "N/A")
                            + " | Temp " + (vitals.temperatureCelsius() != null ? vitals.temperatureCelsius() : "N/A");
                })
                .toList();

        String clinicalSummary = buildClinicalSummary(referral, encounter);

        PrintableReferral printable = new PrintableReferral(
                referral.getId(),
                referral.getPatientId(),
                patient.getMrn(),
                patient.getFullName(),
                patient.getDateOfBirth(),
                patient.getSex() != null ? patient.getSex().name() : null,
                clinicName,
                referral.getReferredToFacility(),
                referral.getSpecialty(),
                referral.getReason(),
                referral.getReferredAt() != null
                        ? referral.getReferredAt().atZone(ZoneId.systemDefault()).toLocalDate()
                        : LocalDate.now(),
                referral.getReferredByName(),
                referral.getReferredById(),
                clinicalSummary,
                diagnosisLines,
                medicationLines,
                emergencyData.knownAllergies(),
                vitalsLines,
                referral.getNotes()
        );
        log.info("Referral printable generated referralId={} patientId={}", referralId, referral.getPatientId());
        return printable;
    }

    @Transactional
    public ReferralRecord markPrinted(UUID referralId) {
        auditGuard.assertSessionActive();
        assertClinicalRole();

        ReferralRecord referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ReferralPrintException("Referral not found"));
        String actor = sessionContext.username() != null ? sessionContext.username() : "UNKNOWN";
        referral.markPrinted(actor, actor);
        referral = referralRepository.save(referral);
        log.info("Referral marked printed referralId={} patientId={} by={}",
                referralId, referral.getPatientId(), actor);

        auditService.record(
                "REFERRAL_PRINTED",
                referral.getPatientId(),
                "referralId=" + referralId + ", destination=" + referral.getReferredToFacility()
        );
        return referral;
    }

    private String buildClinicalSummary(ReferralRecord referral, Encounter encounter) {
        if (encounter != null && encounter.getFinalNote() != null && !encounter.getFinalNote().isBlank()) {
            return encounter.getFinalNote();
        }
        if (encounter != null && encounter.getPhysicianAuthoredNote() != null && !encounter.getPhysicianAuthoredNote().isBlank()) {
            return encounter.getPhysicianAuthoredNote();
        }
        if (referral.getNotes() != null && !referral.getNotes().isBlank()) {
            return referral.getNotes();
        }
        return "Clinical summary not provided.";
    }

    private void assertClinicalRole() {
        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.NURSE && role != Role.ADMIN) {
            throw new ReferralPrintException("Only clinical staff can print referrals");
        }
    }

    public record PrintableReferral(
            UUID referralId,
            UUID patientId,
            String patientMrn,
            String patientName,
            LocalDate patientDateOfBirth,
            String patientSex,
            String sourceFacilityName,
            String destinationFacilityName,
            String specialty,
            String reasonForReferral,
            LocalDate referralDate,
            String orderedByName,
            String orderedByStaffId,
            String clinicalSummary,
            List<String> activeDiagnoses,
            List<String> currentMedications,
            String knownAllergies,
            List<String> recentVitals,
            String additionalNotes
    ) {
    }

    public static class ReferralPrintException extends RuntimeException {
        public ReferralPrintException(String message) {
            super(message);
        }
    }
}
