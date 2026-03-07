package dalili.com.base.application.service;


import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.patient.repository.PatientRepository;
import dalili.com.base.infra.audit.AuditService;
import dalili.com.base.interfaces.security.AuditGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class PatientService {

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

        Patient patient = findByIdInternal(patientId);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patientId, "Patient record accessed");

        return patient;
    }

    /**
     * Open patient file by MRN.
     * Requires active session, creates audit trail, updates last accessed.
     */
    @Transactional
    public Patient openPatientFileByMrn(String mrn) {
        auditGuard.assertSessionActive();

        Patient patient = findByMrnInternal(mrn);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patient.getId(), "Patient record accessed by MRN: " + mrn);

        return patient;
    }

    /**
     * Open patient file by National ID.
     * Requires active session, creates audit trail, updates last accessed.
     */
    @Transactional
    public Patient openPatientFileByNationalId(String nationalId) {
        auditGuard.assertSessionActive();

        Patient patient = findByNationalIdInternal(nationalId);
        patient.recordAccess();
        patientRepository.save(patient);

        auditService.record("PATIENT_FILE_OPENED", patient.getId(), "Patient record accessed by National ID");

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

        if (patientRepository.existsByMrn(mrn)) {
            throw new PatientException("MRN already exists: " + mrn);
        }

        Patient patient = Patient.register(mrn, givenName, familyName, dateOfBirth, sex);
        patient = patientRepository.save(patient);

        auditService.record("PATIENT_REGISTERED", patient.getId(), "New patient registered: " + mrn);

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

        if (patientRepository.existsByMrn(mrn)) {
            throw new PatientException("MRN already exists: " + mrn);
        }

        if (nationalId != null && patientRepository.existsByNationalId(nationalId)) {
            throw new PatientException("National ID already exists: " + nationalId);
        }

        Patient patient = Patient.register(mrn, givenName, familyName, dateOfBirth, sex);
        patient.setNationalId(nationalId);
        patient.setMiddleName(middleName);
        patient.setPhoneNumber(phoneNumber);
        patient.setEmail(email);
        patient.setAddress(address);
        patient.setEmergencyContact(emergencyContactName, emergencyContactPhone);

        patient = patientRepository.save(patient);

        auditService.record("PATIENT_REGISTERED", patient.getId(), "New patient registered: " + mrn);

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
        Patient patient = patientRepository.findByMrnAndActiveTrue(mrn)
                .orElseThrow(() -> new PatientException("Patient not found"));

        if (!patient.getDateOfBirth().equals(dateOfBirth)) {
            throw new PatientException("Verification failed");
        }

        return patient;
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

    // ==================== UPDATES ====================

    @Transactional
    public Patient updateContactInfo(UUID patientId, String phone, String email, String address) {
        auditGuard.assertSessionActive();

        Patient patient = findByIdInternal(patientId);
        patient.setPhoneNumber(phone);
        patient.setEmail(email);
        patient.setAddress(address);

        auditService.record("PATIENT_UPDATED", patientId, "Contact info updated");

        return patientRepository.save(patient);
    }

    @Transactional
    public Patient updateEmergencyContact(UUID patientId, String name, String phone) {
        auditGuard.assertSessionActive();

        Patient patient = findByIdInternal(patientId);
        patient.setEmergencyContact(name, phone);

        auditService.record("PATIENT_UPDATED", patientId, "Emergency contact updated");

        return patientRepository.save(patient);
    }

    @Transactional
    public Patient updateNationalId(UUID patientId, String nationalId) {
        auditGuard.assertSessionActive();

        if (patientRepository.existsByNationalId(nationalId)) {
            throw new PatientException("National ID already exists");
        }

        Patient patient = findByIdInternal(patientId);
        patient.setNationalId(nationalId);

        auditService.record("PATIENT_UPDATED", patientId, "National ID updated");

        return patientRepository.save(patient);
    }

    // ==================== CONSENT ====================

    @Transactional
    public Patient recordConsent(UUID patientId) {
        auditGuard.assertSessionActive();

        Patient patient = findByIdInternal(patientId);
        patient.recordConsent();

        auditService.record("PATIENT_CONSENT_RECORDED", patientId, "Patient gave consent");

        return patientRepository.save(patient);
    }

    // ==================== DEACTIVATION ====================

    @Transactional
    public Patient deactivate(UUID patientId) {
        auditGuard.assertSessionActive();

        Patient patient = findByIdInternal(patientId);
        patient.deactivate();

        auditService.record("PATIENT_DEACTIVATED", patientId, "Patient record deactivated");

        return patientRepository.save(patient);
    }

    // ==================== EXCEPTION ====================

    public static class PatientException extends RuntimeException {
        public PatientException(String message) {
            super(message);
        }
    }
}