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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionActivityService sessionActivityService;
    private final PatientService patientService;
    private final Set<String> allowedClinicNames;
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
            @Value("${dalili.clinic.allowed-names:}") String allowedClinicNamesRaw,
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
        this.allowedClinicNames = parseAllowedClinicNames(clinicName, allowedClinicNamesRaw);
        this.kioskAutoProvisionDefault = kioskAutoProvisionDefault;
        this.defaultKioskDeviceId = defaultKioskDeviceId;
        this.defaultKioskDeviceSecret = defaultKioskDeviceSecret;
        this.defaultKioskLocationDescription = defaultKioskLocationDescription;
    }

    /**
     * Staff login
     */
    public String loginStaff(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        User user = userRepository.findByUsernameIgnoreCaseAndActiveTrue(normalizedUsername)
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
        String normalizedUsername = normalizeUsername(username);
        User user = userRepository.findByUsernameIgnoreCaseAndActiveTrue(normalizedUsername)
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
    public User registerStaff(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            Role role
    ) {
        if (role == Role.PATIENT || role == Role.KIOSK || role == Role.SYSTEM || role == Role.ADMIN || role == Role.SUPER_ADMIN) {
            throw new AuthenticationException("Invalid role for staff registration");
        }

        validatePersonName(firstName, lastName);
        validatePassword(password);
        validateUsername(username);
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validateUsernamePrefix(role, normalizedUsername);
        assertUsernameAvailable(normalizedUsername);
        assertEmailAvailable(normalizedEmail);

        User user = User.createStaff(
                normalizedUsername,
                passwordEncoder.encode(password),
                firstName.trim(),
                lastName.trim(),
                normalizedEmail,
                role
        );
        User saved = userRepository.save(user);
        log.info("Staff user registered userId={} role={}", saved.getId(), saved.getRole());
        return saved;
    }

    /**
     * Bootstrap the first super admin account.
     * This flow is intended for initial system setup only.
     */
    public User bootstrapFirstSuperAdmin(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String company
    ) {
        validateAdminRegistrationInput(username, firstName, lastName, email, password, company);

        if (userRepository.countByRoleAndActiveTrue(Role.SUPER_ADMIN) > 0) {
            throw new AuthenticationException("Super admin already exists");
        }

        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validateUsernamePrefix(Role.SUPER_ADMIN, normalizedUsername);
        assertUsernameAvailable(normalizedUsername);
        assertEmailAvailable(normalizedEmail);

        User user = User.createStaff(
                normalizedUsername,
                passwordEncoder.encode(password),
                firstName.trim(),
                lastName.trim(),
                normalizedEmail,
                Role.SUPER_ADMIN
        );
        User saved = userRepository.save(user);
        log.info("Initial super admin bootstrapped userId={} username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * Register a new admin account.
     * Access control for this method is enforced at controller/security level.
     */
    public User registerAdmin(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String company
    ) {
        validateAdminRegistrationInput(username, firstName, lastName, email, password, company);

        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);
        validateUsernamePrefix(Role.ADMIN, normalizedUsername);
        assertUsernameAvailable(normalizedUsername);
        assertEmailAvailable(normalizedEmail);

        User user = User.createStaff(
                normalizedUsername,
                passwordEncoder.encode(password),
                firstName.trim(),
                lastName.trim(),
                normalizedEmail,
                Role.ADMIN
        );
        User saved = userRepository.save(user);
        log.info("Admin account registered userId={} username={}", saved.getId(), saved.getUsername());
        return saved;
    }

    /**
     * Returns true only when no active super-admin account exists.
     */
    public boolean isSuperAdminBootstrapAllowed() {
        return userRepository.countByRoleAndActiveTrue(Role.SUPER_ADMIN) == 0;
    }

    /**
     * Register patient user (links to existing Patient record)
     */
    public User registerPatientUser(String username, String password, UUID patientId) {
        validatePassword(password);
        validateUsername(username);
        String normalizedUsername = normalizeUsername(username);
        assertUsernameAvailable(normalizedUsername);

        Patient patient = patientService.findById(patientId);

        User user = User.createPatient(
                normalizedUsername,
                passwordEncoder.encode(password),
                patient.getGivenName(),
                patient.getFamilyName(),
                null,
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

    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        if (userId == null) {
            throw new AuthenticationException("No active session");
        }

        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new AuthenticationException("Session user not found"));

        if (user.isSystem()) {
            throw new AuthenticationException("System accounts cannot change password from this flow");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthenticationException("Current password is incorrect");
        }

        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new AuthenticationException("New password must be different from current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed userId={} actorType={}", user.getId(), user.getActorType());
    }

    private void validateAdminRegistrationInput(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String company
    ) {
        validatePersonName(firstName, lastName);
        validateUsername(username);
        validateEmail(email);
        validatePassword(password);
        if (isBlank(company)) {
            throw new AuthenticationException("Company is required");
        }

        if (!isCompanyAllowed(company)) {
            throw new AuthenticationException("Company is not configured for this deployment");
        }
    }

    private void validatePersonName(String firstName, String lastName) {
        if (isBlank(firstName) || firstName.trim().length() < 2) {
            throw new AuthenticationException("First name is required");
        }
        if (isBlank(lastName) || lastName.trim().length() < 2) {
            throw new AuthenticationException("Last name is required");
        }
    }

    private void validatePassword(String password) {
        if (isBlank(password) || password.length() < 8) {
            throw new AuthenticationException("Password must be at least 8 characters");
        }
    }

    private void validateUsername(String username) {
        if (isBlank(username) || username.trim().length() < 3) {
            throw new AuthenticationException("Username must be at least 3 characters");
        }
    }

    private void validateEmail(String email) {
        if (isBlank(email)) {
            throw new AuthenticationException("Email is required");
        }
        String normalized = email.trim().toLowerCase();
        if (!normalized.matches(EMAIL_REGEX)) {
            throw new AuthenticationException("Email format is invalid");
        }
    }

    private void assertUsernameAvailable(String username) {
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            throw new AuthenticationException("Username already exists");
        }
    }

    private void assertEmailAvailable(String email) {
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AuthenticationException("Email already exists");
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

    private String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase();
    }

    private String normalizeEmail(String email) {
        validateEmail(email);
        return email.trim().toLowerCase();
    }

    private void validateUsernamePrefix(Role role, String normalizedUsername) {
        String requiredPrefix = requiredPrefixForRole(role);
        if (requiredPrefix == null || requiredPrefix.isBlank()) {
            return;
        }
        if (normalizedUsername == null || normalizedUsername.isBlank()) {
            throw new AuthenticationException("Username is required");
        }
        String upperUsername = normalizedUsername.toUpperCase(Locale.ROOT);
        if (!upperUsername.startsWith(requiredPrefix)) {
            throw new AuthenticationException("Username must start with " + requiredPrefix + " for role " + role.name());
        }
    }

    private String requiredPrefixForRole(Role role) {
        return switch (role) {
            case SUPER_ADMIN -> "SA";
            case ADMIN -> "AD";
            case NURSE -> "NS";
            case PHYSICIAN -> "CL";
            case RECEPTIONIST -> "RC";
            default -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Set<String> parseAllowedClinicNames(String clinicName, String allowedClinicNamesRaw) {
        Set<String> configured = Arrays.stream((allowedClinicNamesRaw == null ? "" : allowedClinicNamesRaw).split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        if (!configured.isEmpty()) {
            return configured;
        }

        if (clinicName != null && !clinicName.trim().isBlank()) {
            return Set.of(clinicName.trim().toLowerCase(Locale.ROOT));
        }

        return Set.of();
    }

    private boolean isCompanyAllowed(String company) {
        if (allowedClinicNames.isEmpty()) {
            return true;
        }
        return allowedClinicNames.contains(company.trim().toLowerCase(Locale.ROOT));
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
