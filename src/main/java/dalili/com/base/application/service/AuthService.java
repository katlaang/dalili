package dalili.com.base.application.service;

import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.domain.user.model.User;
import dalili.com.base.domain.user.repository.UserRepository;
import dalili.com.base.interfaces.security.jwt.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionActivityService sessionActivityService;
    private final PatientService patientService;
    private final String clinicName;
    private final boolean kioskAutoProvisionDefault;
    private final String defaultKioskDeviceId;
    private final String defaultKioskDeviceSecret;
    private final String defaultKioskLocationDescription;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SessionActivityService sessionActivityService,
            PatientService patientService,
            @Value("${dalili.clinic.name:Dalili Health Clinic}") String clinicName,
            @Value("${dalili.kiosk.auto-provision-default:true}") boolean kioskAutoProvisionDefault,
            @Value("${dalili.kiosk.default-device-id:kiosk-front-desk-1}") String defaultKioskDeviceId,
            @Value("${dalili.kiosk.default-device-secret:kiosk-secret-change-me}") String defaultKioskDeviceSecret,
            @Value("${dalili.kiosk.default-location-description:Front Desk 1}") String defaultKioskLocationDescription
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionActivityService = sessionActivityService;
        this.patientService = patientService;
        this.clinicName = clinicName;
        this.kioskAutoProvisionDefault = kioskAutoProvisionDefault;
        this.defaultKioskDeviceId = defaultKioskDeviceId;
        this.defaultKioskDeviceSecret = defaultKioskDeviceSecret;
        this.defaultKioskLocationDescription = defaultKioskLocationDescription;
    }

    /**
     * Staff login
     */
    public String loginStaff(String username, String password) {
        User user = userRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        if (!user.isStaff()) {
            throw new AuthenticationException("Invalid credentials");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationException("Invalid credentials");
        }

        JwtService.TokenResult result = jwtService.generateStaffToken(
                user.getId(), user.getUsername(), user.getRole());

        sessionActivityService.touch(result.sessionId(), user.getId(), user.getActorType());
        log.info("Staff authenticated userId={} role={}", user.getId(), user.getRole());

        return result.token();
    }

    /**
     * Patient login (for patient portal/app)
     */
    public String loginPatient(String username, String password) {
        User user = userRepository.findByUsernameAndActiveTrue(username)
                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));

        if (!user.isPatient()) {
            throw new AuthenticationException("Invalid credentials");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationException("Invalid credentials");
        }

        JwtService.TokenResult result = jwtService.generatePatientToken(
                user.getId(), user.getUsername(), user.getPatientId());

        sessionActivityService.touch(result.sessionId(), user.getId(), user.getActorType());
        log.info("Patient authenticated patientId={}", user.getPatientId());

        return result.token();
    }

    /**
     * Kiosk device login (device identity only, no patient context).
     */
    public String loginKioskDevice(String deviceId, String deviceSecret) {
        User kiosk = userRepository.findByUsernameAndActiveTrue(deviceId)
                .orElseGet(() -> autoProvisionDefaultKioskForLogin(deviceId, deviceSecret));

        if (kiosk == null) {
            throw new AuthenticationException("Unknown kiosk device");
        }

        if (!kiosk.isKiosk()) {
            throw new AuthenticationException("Invalid device");
        }

        if (!passwordEncoder.matches(deviceSecret, kiosk.getPasswordHash())) {
            throw new AuthenticationException("Invalid credentials");
        }

        JwtService.TokenResult result = jwtService.generateKioskToken(
                kiosk.getId(), kiosk.getUsername(), null);

        sessionActivityService.touch(result.sessionId(), kiosk.getId(), kiosk.getActorType());
        log.info("Kiosk device authenticated kioskUserId={}", kiosk.getId());

        return result.token();
    }

    /**
     * Kiosk check-in - patient identifies with MRN + DOB
     */
    public String kioskCheckIn(String kioskDeviceId, String mrn, String dobString) {
        User kiosk = resolveKioskDevice(kioskDeviceId);

        LocalDate dob = parseDateOfBirth(dobString);

        Patient patient;
        try {
            patient = patientService.verifyForKioskCheckIn(mrn, dob);
        } catch (PatientService.PatientException e) {
            throw new AuthenticationException("Invalid credentials");
        }

        JwtService.TokenResult result = jwtService.generateKioskToken(
                kiosk.getId(), kioskDeviceId, patient.getId());

        sessionActivityService.createKioskSession(result.sessionId(), kiosk.getId(), patient.getId());
        log.info("Kiosk check-in authenticated kioskUserId={} patientId={}", kiosk.getId(), patient.getId());
        return result.token();
    }

    /**
     * Kiosk check-in by patient demographics. Creates patient record if not found.
     */
    public String kioskIdentifyByName(
            String kioskDeviceId,
            String givenName,
            String familyName,
            String dobString,
            Patient.Sex sex
    ) {
        User kiosk = resolveKioskDevice(kioskDeviceId);
        LocalDate dob = parseDateOfBirth(dobString);

        Patient patient;
        try {
            patient = patientService.resolveOrRegisterForKioskCheckIn(givenName, familyName, dob, sex);
        } catch (PatientService.PatientException e) {
            throw new AuthenticationException(e.getMessage());
        }

        JwtService.TokenResult result = jwtService.generateKioskToken(
                kiosk.getId(), kioskDeviceId, patient.getId());

        sessionActivityService.createKioskSession(result.sessionId(), kiosk.getId(), patient.getId());
        log.info("Kiosk identify authenticated kioskUserId={} patientId={}", kiosk.getId(), patient.getId());
        return result.token();
    }

    /**
     * Register staff user
     */
    public User registerStaff(String username, String password, String fullName, Role role) {
        if (role == Role.PATIENT || role == Role.KIOSK || role == Role.SYSTEM || role == Role.ADMIN || role == Role.SUPER_ADMIN) {
            throw new AuthenticationException("Invalid role for staff registration");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new AuthenticationException("Username already exists");
        }

        User user = User.createStaff(username, passwordEncoder.encode(password), fullName, role);
        User saved = userRepository.save(user);
        log.info("Staff user registered userId={} role={}", saved.getId(), saved.getRole());
        return saved;
    }

    /**
     * Bootstrap the first super admin account.
     * This flow is intended for initial system setup only.
     */
    public User bootstrapFirstSuperAdmin(String fullName, String password, String company) {
        validateAdminRegistrationInput(fullName, password, company);

        if (userRepository.countByRoleAndActiveTrue(Role.SUPER_ADMIN) > 0) {
            throw new AuthenticationException("Super admin already exists");
        }

        String username = generateUniqueUsername("super-admin", fullName);
        User user = User.createStaff(username, passwordEncoder.encode(password), fullName.trim(), Role.SUPER_ADMIN);
        User saved = userRepository.save(user);
        log.info("Initial super admin bootstrapped userId={} username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * Register a new admin account.
     * Access control for this method is enforced at controller/security level.
     */
    public User registerAdmin(String fullName, String password, String company) {
        validateAdminRegistrationInput(fullName, password, company);

        String username = generateUniqueUsername("admin", fullName);
        User user = User.createStaff(username, passwordEncoder.encode(password), fullName.trim(), Role.ADMIN);
        User saved = userRepository.save(user);
        log.info("Admin account registered userId={} username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * Register patient user (links to existing Patient record)
     */
    public User registerPatientUser(String username, String password, UUID patientId) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new AuthenticationException("Username already exists");
        }

        Patient patient = patientService.findById(patientId);

        User user = User.createPatient(
                username,
                passwordEncoder.encode(password),
                patient.getFullName(),
                patientId
        );
        User saved = userRepository.save(user);
        log.info("Patient portal user registered userId={} patientId={}", saved.getId(), patientId);
        return saved;
    }

    /**
     * Register kiosk device
     */
    public User registerKiosk(String deviceId, String deviceSecret, String locationDescription) {
        if (userRepository.findByUsername(deviceId).isPresent()) {
            throw new AuthenticationException("Device ID already exists");
        }

        User user = User.createKiosk(deviceId, passwordEncoder.encode(deviceSecret), locationDescription);
        User saved = userRepository.save(user);
        log.info("Kiosk device registered userId={} deviceId={}", saved.getId(), deviceId);
        return saved;
    }

    /**
     * Logout
     */
    public void logout(UUID sessionId) {
        sessionActivityService.invalidateSession(sessionId);
        log.info("Session logout completed sessionId={}", sessionId);
    }

    private void validateAdminRegistrationInput(String fullName, String password, String company) {
        if (isBlank(fullName) || fullName.trim().length() < 2) {
            throw new AuthenticationException("Name is required");
        }
        if (isBlank(password) || password.length() < 8) {
            throw new AuthenticationException("Password must be at least 8 characters");
        }
        if (isBlank(company)) {
            throw new AuthenticationException("Company is required");
        }

        String expectedCompany = clinicName == null ? "" : clinicName.trim();
        if (!expectedCompany.equalsIgnoreCase(company.trim())) {
            throw new AuthenticationException("Company does not match this deployment");
        }
    }

    private LocalDate parseDateOfBirth(String dobString) {
        try {
            if (dobString.length() == 8 && !dobString.contains("-")) {
                return LocalDate.parse(dobString, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(dobString, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            log.warn("Invalid date-of-birth format provided");
            throw new AuthenticationException("Invalid date format. Use YYYYMMDD or YYYY-MM-DD");
        }
    }

    private User resolveKioskDevice(String kioskDeviceId) {
        User kiosk = userRepository.findByUsernameAndActiveTrue(kioskDeviceId)
                .orElseThrow(() -> new AuthenticationException("Unknown kiosk device"));

        if (!kiosk.isKiosk()) {
            throw new AuthenticationException("Invalid device");
        }
        return kiosk;
    }

    private User autoProvisionDefaultKioskForLogin(String deviceId, String deviceSecret) {
        if (!kioskAutoProvisionDefault) {
            return null;
        }
        if (isBlank(defaultKioskDeviceId) || isBlank(defaultKioskDeviceSecret)) {
            return null;
        }
        if (!defaultKioskDeviceId.equals(deviceId) || !defaultKioskDeviceSecret.equals(deviceSecret)) {
            return null;
        }

        try {
            User kiosk = User.createKiosk(
                    defaultKioskDeviceId,
                    passwordEncoder.encode(defaultKioskDeviceSecret),
                    isBlank(defaultKioskLocationDescription) ? "Front Desk 1" : defaultKioskLocationDescription.trim()
            );
            User saved = userRepository.save(kiosk);
            log.info("Default kiosk auto-provisioned during login deviceId={}", defaultKioskDeviceId);
            return saved;
        } catch (DataIntegrityViolationException collision) {
            return userRepository.findByUsernameAndActiveTrue(defaultKioskDeviceId).orElse(null);
        }
    }

    private String generateUniqueUsername(String prefix, String fullName) {
        String normalized = fullName == null ? "" : fullName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        if (normalized.isBlank()) {
            normalized = "user";
        }

        String base = prefix + "-" + normalized;
        String candidate = base;
        int counter = 2;

        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + "-" + counter;
            counter++;
        }

        return candidate;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
