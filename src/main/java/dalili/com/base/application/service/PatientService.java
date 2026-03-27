package dalili.com.base.application.service;

import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.patient.repository.PatientRepository;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository patientRepository;
    private final AuditService auditService;
    private final AuditGuard auditGuard;

    public PatientService(
            PatientRepository patientRepository,
            AuditService auditService,
            AuditGuard auditGuard
    ) {
        this.patientRepository = patientRepository;
        this.auditService = auditService;
        this.auditGuard = auditGuard;
    }

    // ==================== ACCESS (audited) ====================

    /**
     * Open patient file by ID.
     * Requires active session, creates audit trail, updates last accessed.
     */
    @Transactional
    public Patient openPatientFile(UUID patientId) {
        auditGuard.assertSessionActive();
        log.info("Opening patient file by id patientId={}", patientId);

        Patient patient = findByIdInternal(patientId);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patientId, "Patient record accessed");
        log.info("Patient file opened patientId={}", patientId);

        return patient;
    }

    /**
     * Open patient file by MRN.
     * Requires active session, creates audit trail, updates last accessed.
     */
    @Transactional
    public Patient openPatientFileByMrn(String mrn) {
        auditGuard.assertSessionActive();
        log.info("Opening patient file by MRN");

        Patient patient = findByMrnInternal(mrn);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patient.getId(), "Patient record accessed by MRN: " + mrn);
        log.info("Patient file opened by MRN patientId={}", patient.getId());

        return patient;
    }

    /**
     * Open patient file by National ID.
     * Requires active session, creates audit trail, updates last accessed.
     */
    @Transactional
    public Patient openPatientFileByNationalId(String nationalId) {
        auditGuard.assertSessionActive();
        log.info("Opening patient file by national ID");

        Patient patient = findByNationalIdInternal(nationalId);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patient.getId(), "Patient record accessed by National ID");
        log.info("Patient file opened by national ID patientId={}", patient.getId());

        return patient;
    }

    // ==================== REGISTRATION ====================

    @Transactional
    public Patient registerPatient(
            String mrn,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            Patient.Sex sex
    ) {
        auditGuard.assertSessionActive();
        log.info("Registering patient (minimal profile)");

        String normalizedMrn = normalizePatientId(mrn);
        if (patientRepository.existsByMrn(normalizedMrn)) {
            throw new PatientException("Patient ID already exists: " + normalizedMrn);
        }

        Patient patient = Patient.register(normalizedMrn, givenName, familyName, dateOfBirth, sex);
        patient = patientRepository.save(patient);

        auditService.record("PATIENT_REGISTERED", patient.getId(), "New patient registered: " + normalizedMrn);
        log.info("Patient registered patientId={}", patient.getId());

        return patient;
    }

    @Transactional
    public Patient registerPatientFull(
            String mrn,
            String nationalId,
            String givenName,
            String middleName,
            String familyName,
            LocalDate dateOfBirth,
            Patient.Sex sex,
            String phoneNumber,
            String email,
            String address,
            String emergencyContactName,
            String emergencyContactPhone
    ) {
        auditGuard.assertSessionActive();
        log.info("Registering patient (full profile)");

        String normalizedMrn = normalizePatientId(mrn);
        if (patientRepository.existsByMrn(normalizedMrn)) {
            throw new PatientException("Patient ID already exists: " + normalizedMrn);
        }

        if (nationalId != null && patientRepository.existsByNationalId(nationalId)) {
            throw new PatientException("National ID already exists: " + nationalId);
        }

        Patient patient = Patient.register(normalizedMrn, givenName, familyName, dateOfBirth, sex);
        patient.setNationalId(nationalId);
        patient.setMiddleName(middleName);
        patient.setPhoneNumber(phoneNumber);
        patient.setEmail(email);
        patient.setAddress(address);
        patient.setEmergencyContact(emergencyContactName, emergencyContactPhone);

        patient = patientRepository.save(patient);

        auditService.record("PATIENT_REGISTERED", patient.getId(), "New patient registered: " + normalizedMrn);
        log.info("Patient registered with full profile patientId={}", patient.getId());

        return patient;
    }

    // ==================== INTERNAL LOOKUP (no audit, no session check) ====================

    /**
     * Internal lookup by ID - for system use only (e.g., auth service validation)
     */
    public Patient findById(UUID patientId) {
        return findByIdInternal(patientId);
    }

    /**
     * Verify patient for kiosk check-in (MRN + DOB)
     * No audit - audit happens at check-in completion
     */
    public Patient verifyForKioskCheckIn(String mrn, LocalDate dateOfBirth) {
        log.info("Verifying kiosk check-in by MRN");
        Patient patient = patientRepository.findByMrnAndActiveTrue(mrn)
                .orElseThrow(() -> new PatientException("Patient not found"));

        if (!patient.getDateOfBirth().equals(dateOfBirth)) {
            log.warn("Kiosk check-in verification failed for patientId={}", patient.getId());
            throw new PatientException("Verification failed");
        }

        log.info("Kiosk check-in verification succeeded patientId={}", patient.getId());
        return patient;
    }

    /**
     * Front-desk verification by patient ID + DOB.
     * Returns limited patient identity for receptionist workflows without opening full chart context.
     */
    public Patient findForFrontDeskLookup(String mrn, LocalDate dateOfBirth) {
        String normalizedMrn = normalizePatientId(mrn);
        Patient patient = patientRepository.findByMrnAndActiveTrue(normalizedMrn)
                .orElseThrow(() -> new PatientException("Patient not found"));

        if (!patient.getDateOfBirth().equals(dateOfBirth)) {
            throw new PatientException("Patient number and date of birth do not match");
        }
        return patient;
    }

    /**
     * Resolves an existing patient by demographics or creates a new record for kiosk flow.
     */
    @Transactional
    public Patient resolveOrRegisterForKioskCheckIn(
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            Patient.Sex sex
    ) {
        log.info("Resolving kiosk patient by demographics");
        String normalizedGivenName = normalizeName(givenName, "Given name is required");
        String normalizedFamilyName = normalizeName(familyName, "Family name is required");
        Patient.Sex resolvedSex = sex != null ? sex : Patient.Sex.UNKNOWN;

        return patientRepository
                .findFirstByGivenNameIgnoreCaseAndFamilyNameIgnoreCaseAndDateOfBirthAndSexAndActiveTrue(
                        normalizedGivenName,
                        normalizedFamilyName,
                        dateOfBirth,
                        resolvedSex
                )
                .orElseGet(() -> {
                    String generatedMrn = generateKioskPatientId();
                    Patient created = Patient.register(
                            generatedMrn,
                            normalizedGivenName,
                            normalizedFamilyName,
                            dateOfBirth,
                            resolvedSex
                    );
                    Patient saved = patientRepository.save(created);
                    auditService.record(
                            "PATIENT_REGISTERED_KIOSK",
                            saved.getId(),
                            "Kiosk self-check-in patient registration: " + saved.getMrn()
                    );
                    log.info("Kiosk patient auto-registered patientId={}", saved.getId());
                    return saved;
                });
    }

    private Patient findByIdInternal(UUID patientId) {
        return patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientException("Patient not found"));
    }

    private Patient findByMrnInternal(String mrn) {
        return patientRepository.findByMrnAndActiveTrue(mrn)
                .orElseThrow(() -> new PatientException("Patient not found"));
    }

    private Patient findByNationalIdInternal(String nationalId) {
        return patientRepository.findByNationalId(nationalId)
                .orElseThrow(() -> new PatientException("Patient not found"));
    }

    private String normalizeName(String value, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw new PatientException(errorMessage);
        }
        return value.trim();
    }

    private String normalizePatientId(String mrn) {
        if (mrn == null || mrn.isBlank()) {
            throw new PatientException("Patient ID is required");
        }
        String normalized = mrn.trim();
        if (!normalized.matches("\\d{8}")) {
            throw new PatientException("Patient ID must be exactly 8 digits");
        }
        return normalized;
    }

    private String generateKioskPatientId() {
        String generated;
        do {
            int value = java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 100_000_000);
            generated = String.format("%08d", value);
        } while (patientRepository.existsByMrn(generated));
        return generated;
    }

    // ==================== UPDATES ====================

    @Transactional
    public Patient updateContactInfo(UUID patientId, String phone, String email, String address) {
        auditGuard.assertSessionActive();
        log.info("Updating patient contact info patientId={}", patientId);

        Patient patient = findByIdInternal(patientId);
        patient.setPhoneNumber(phone);
        patient.setEmail(email);
        patient.setAddress(address);

        auditService.record("PATIENT_UPDATED", patientId, "Contact info updated");

        Patient saved = patientRepository.save(patient);
        log.info("Patient contact info updated patientId={}", saved.getId());
        return saved;
    }

    @Transactional
    public Patient updateEmergencyContact(UUID patientId, String name, String phone) {
        auditGuard.assertSessionActive();
        log.info("Updating emergency contact patientId={}", patientId);

        Patient patient = findByIdInternal(patientId);
        patient.setEmergencyContact(name, phone);

        auditService.record("PATIENT_UPDATED", patientId, "Emergency contact updated");

        Patient saved = patientRepository.save(patient);
        log.info("Emergency contact updated patientId={}", saved.getId());
        return saved;
    }

    @Transactional
    public Patient updateNationalId(UUID patientId, String nationalId) {
        auditGuard.assertSessionActive();
        log.info("Updating national ID patientId={}", patientId);

        if (patientRepository.existsByNationalId(nationalId)) {
            throw new PatientException("National ID already exists");
        }

        Patient patient = findByIdInternal(patientId);
        patient.setNationalId(nationalId);

        auditService.record("PATIENT_UPDATED", patientId, "National ID updated");

        Patient saved = patientRepository.save(patient);
        log.info("National ID updated patientId={}", saved.getId());
        return saved;
    }

    // ==================== CONSENT ====================

    @Transactional
    public Patient recordConsent(UUID patientId) {
        auditGuard.assertSessionActive();
        log.info("Recording patient consent patientId={}", patientId);

        Patient patient = findByIdInternal(patientId);
        patient.recordConsent();

        auditService.record("PATIENT_CONSENT_RECORDED", patientId, "Patient gave consent");

        Patient saved = patientRepository.save(patient);
        log.info("Patient consent recorded patientId={}", saved.getId());
        return saved;
    }

    // ==================== DEACTIVATION ====================

    @Transactional
    public Patient deactivate(UUID patientId) {
        auditGuard.assertSessionActive();
        log.info("Deactivating patient record patientId={}", patientId);

        Patient patient = findByIdInternal(patientId);
        patient.deactivate();

        auditService.record("PATIENT_DEACTIVATED", patientId, "Patient record deactivated");

        Patient saved = patientRepository.save(patient);
        log.info("Patient record deactivated patientId={}", saved.getId());
        return saved;
    }

    // ==================== EXCEPTION ====================

    public static class PatientException extends RuntimeException {
        public PatientException(String message) {
            super(message);
        }
    }
}


