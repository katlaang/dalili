package dalili.com.base.application.service;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.domain.patient.model.LabResultRecord;
import dalili.com.base.domain.patient.model.ReferralRecord;
import dalili.com.base.domain.patient.repository.LabResultRecordRepository;
import dalili.com.base.domain.patient.repository.ReferralRecordRepository;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Maintains lab and referral records accessible from patient and clinician portals.
 */
@Service
public class PatientClinicalRecordsService {

    private static final Logger log = LoggerFactory.getLogger(PatientClinicalRecordsService.class);

    private final LabResultRecordRepository labResultRepository;
    private final ReferralRecordRepository referralRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;
    private final SessionContext sessionContext;

    public PatientClinicalRecordsService(
            LabResultRecordRepository labResultRepository,
            ReferralRecordRepository referralRepository,
            AuditService auditService,
            AuditGuard auditGuard,
            SessionContext sessionContext
    ) {
        this.labResultRepository = labResultRepository;
        this.referralRepository = referralRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
        this.sessionContext = sessionContext;
    }

    @Transactional
    public LabResultRecord addLabResult(UUID patientId, LabResultInput input) {
        auditGuard.assertSessionActive();
        assertClinicalRole();
        log.info("Adding lab result record patientId={} encounterId={}", patientId, input == null ? null : input.encounterId());

        LabResultRecord record = LabResultRecord.create(
                patientId,
                input.encounterId(),
                input.testName(),
                input.resultValue(),
                input.unit(),
                input.referenceRange(),
                input.interpretation(),
                input.criticalResult(),
                sessionContext.username(),
                sessionContext.username()
        );
        record = labResultRepository.save(record);

        auditService.record("PATIENT_LAB_RESULT_ADDED", patientId,
                "Lab result added: " + record.getTestName());
        log.info("Lab result record added patientId={} labRecordId={}", patientId, record.getId());

        return record;
    }

    public List<LabResultRecord> getLabResults(UUID patientId, int limit) {
        auditGuard.assertSessionActive();
        List<LabResultRecord> results = labResultRepository.findByPatientIdOrderByRecordedAtDesc(patientId)
                .stream()
                .limit(limit)
                .toList();
        auditService.record("PATIENT_LAB_RESULTS_VIEWED", patientId,
                "Lab result history viewed count=" + results.size());
        log.info("Lab results fetched patientId={} count={}", patientId, results.size());
        return results;
    }

    @Transactional
    public ReferralRecord addReferral(UUID patientId, ReferralInput input) {
        auditGuard.assertSessionActive();
        assertClinicalRole();
        log.info("Adding referral record patientId={} encounterId={}", patientId, input == null ? null : input.encounterId());

        ReferralRecord referral = ReferralRecord.create(
                patientId,
                input.encounterId(),
                input.referredToFacility(),
                input.specialty(),
                input.reason(),
                sessionContext.username(),
                sessionContext.username(),
                input.notes(),
                input.destinationUsesDalili()
        );
        referral = referralRepository.save(referral);

        auditService.record("PATIENT_REFERRAL_ADDED", patientId,
                "Referral created to " + referral.getReferredToFacility());
        log.info("Referral record added patientId={} referralId={} destinationUsesDalili={}",
                patientId, referral.getId(), referral.getDestinationUsesDalili());

        return referral;
    }

    @Transactional
    public ReferralRecord updateReferralStatus(UUID referralId, ReferralRecord.ReferralStatus status, String notes) {
        auditGuard.assertSessionActive();
        assertClinicalRole();
        log.info("Updating referral status referralId={} status={}", referralId, status);

        ReferralRecord referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ClinicalRecordException("Referral not found"));
        referral.updateStatus(status, notes);
        referral = referralRepository.save(referral);

        auditService.record("PATIENT_REFERRAL_UPDATED", referral.getPatientId(),
                "Referral status updated to " + referral.getStatus());
        log.info("Referral status updated referralId={} patientId={} status={}",
                referral.getId(), referral.getPatientId(), referral.getStatus());

        return referral;
    }

    public List<ReferralRecord> getReferrals(UUID patientId, int limit) {
        auditGuard.assertSessionActive();
        List<ReferralRecord> referrals = referralRepository.findByPatientIdOrderByReferredAtDesc(patientId)
                .stream()
                .limit(limit)
                .toList();
        auditService.record("PATIENT_REFERRALS_VIEWED", patientId,
                "Referral history viewed count=" + referrals.size());
        log.info("Referral records fetched patientId={} count={}", patientId, referrals.size());
        return referrals;
    }

    private void assertClinicalRole() {
        Role role = sessionContext.role();
        if (role != Role.PHYSICIAN && role != Role.NURSE && role != Role.ADMIN) {
            throw new ClinicalRecordException("Only clinical staff can manage lab/referral records");
        }
    }

    public record LabResultInput(
            UUID encounterId,
            String testName,
            String resultValue,
            String unit,
            String referenceRange,
            String interpretation,
            boolean criticalResult
    ) {
    }

    public record ReferralInput(
            UUID encounterId,
            String referredToFacility,
            String specialty,
            String reason,
            String notes,
            Boolean destinationUsesDalili
    ) {
    }

    public static class ClinicalRecordException extends RuntimeException {
        public ClinicalRecordException(String message) {
            super(message);
        }
    }
}


