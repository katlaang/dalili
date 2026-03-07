package dalili.com.base.domain.patient;

import dalili.com.base.application.service.PatientService;
import dalili.com.base.domain.patient.model.Patient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    // ==================== REGISTRATION ====================

    @PostMapping("/register")
    public ResponseEntity<PatientResponse> register(@RequestBody RegisterPatientRequest request) {
        try {
            Patient patient = patientService.registerPatient(
                    request.mrn(),
                    request.givenName(),
                    request.familyName(),
                    request.dateOfBirth(),
                    request.sex()
            );
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/register/full")
    public ResponseEntity<PatientResponse> registerFull(@RequestBody RegisterPatientFullRequest request) {
        try {
            Patient patient = patientService.registerPatientFull(
                    request.mrn(),
                    request.nationalId(),
                    request.givenName(),
                    request.middleName(),
                    request.familyName(),
                    request.dateOfBirth(),
                    request.sex(),
                    request.phoneNumber(),
                    request.email(),
                    request.address(),
                    request.emergencyContactName(),
                    request.emergencyContactPhone()
            );
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ==================== LOOKUP ====================

    @GetMapping("/{id}")
    public ResponseEntity<PatientResponse> getById(@PathVariable UUID id) {
        try {
            Patient patient = patientService.openPatientFile(id);
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/mrn/{mrn}")
    public ResponseEntity<PatientResponse> getByMrn(@PathVariable String mrn) {
        try {
            Patient patient = patientService.openPatientFileByMrn(mrn);
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== UPDATES ====================

    @PutMapping("/{id}/contact")
    public ResponseEntity<PatientResponse> updateContact(
            @PathVariable UUID id,
            @RequestBody UpdateContactRequest request
    ) {
        try {
            Patient patient = patientService.updateContactInfo(
                    id,
                    request.phoneNumber(),
                    request.email(),
                    request.address()
            );
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/emergency-contact")
    public ResponseEntity<PatientResponse> updateEmergencyContact(
            @PathVariable UUID id,
            @RequestBody UpdateEmergencyContactRequest request
    ) {
        try {
            Patient patient = patientService.updateEmergencyContact(
                    id,
                    request.name(),
                    request.phone()
            );
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}/national-id")
    public ResponseEntity<PatientResponse> updateNationalId(
            @PathVariable UUID id,
            @RequestBody UpdateNationalIdRequest request
    ) {
        try {
            Patient patient = patientService.updateNationalId(id, request.nationalId());
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ==================== CONSENT ====================

    @PostMapping("/{id}/consent")
    public ResponseEntity<PatientResponse> recordConsent(@PathVariable UUID id) {
        try {
            Patient patient = patientService.recordConsent(id);
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== DEACTIVATION ====================

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<PatientResponse> deactivate(@PathVariable UUID id) {
        try {
            Patient patient = patientService.deactivate(id);
            return ResponseEntity.ok(PatientResponse.from(patient));
        } catch (PatientService.PatientException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== DTOs ====================

    record RegisterPatientRequest(
            String mrn,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            Patient.Sex sex
    ) {
    }

    record RegisterPatientFullRequest(
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
    }

    record UpdateContactRequest(String phoneNumber, String email, String address) {
    }

    record UpdateEmergencyContactRequest(String name, String phone) {
    }

    record UpdateNationalIdRequest(String nationalId) {
    }

    record PatientResponse(
            UUID id,
            String mrn,
            String nationalId,
            String fullName,
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            Patient.Sex sex,
            String phoneNumber,
            String email,
            String address,
            String emergencyContactName,
            String emergencyContactPhone,
            boolean consentGiven,
            Instant consentAt,
            boolean active,
            Instant registeredAt,
            Instant lastVisitAt
    ) {
        static PatientResponse from(Patient p) {
            return new PatientResponse(
                    p.getId(),
                    p.getMrn(),
                    p.getNationalId(),
                    p.getFullName(),
                    p.getGivenName(),
                    p.getFamilyName(),
                    p.getDateOfBirth(),
                    p.getSex(),
                    p.getPhoneNumber(),
                    p.getEmail(),
                    p.getAddress(),
                    p.getEmergencyContactName(),
                    p.getEmergencyContactPhone(),
                    p.isConsentGiven(),
                    p.getConsentAt(),
                    p.isActive(),
                    p.getRegisteredAt(),
                    p.getLastAccessedAt()
            );
        }
    }
}
