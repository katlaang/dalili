package dalili.com.base.application.service;

import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.domain.user.model.User;
import dalili.com.base.domain.user.repository.UserRepository;
import dalili.com.base.interfaces.security.jwt.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionActivityService sessionActivityService;
    private final PatientService patientService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SessionActivityService sessionActivityService,
            PatientService patientService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionActivityService = sessionActivityService;
        this.patientService = patientService;
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

        return result.token();
    }

    /**
     * Kiosk check-in - patient identifies with MRN + DOB
     */
    public String kioskCheckIn(String kioskDeviceId, String mrn, String dobString) {
        User kiosk = userRepository.findByUsernameAndActiveTrue(kioskDeviceId)
                .orElseThrow(() -> new AuthenticationException("Unknown kiosk device"));

        if (!kiosk.isKiosk()) {
            throw new AuthenticationException("Invalid device");
        }

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

        return result.token();
    }

    /**
     * Register staff user
     */
    public User registerStaff(String username, String password, String fullName, Role role) {
        if (role == Role.PATIENT || role == Role.KIOSK || role == Role.SYSTEM) {
            throw new AuthenticationException("Invalid role for staff registration");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            throw new AuthenticationException("Username already exists");
        }

        User user = User.createStaff(username, passwordEncoder.encode(password), fullName, role);
        return userRepository.save(user);
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
        return userRepository.save(user);
    }

    /**
     * Register kiosk device
     */
    public User registerKiosk(String deviceId, String deviceSecret, String locationDescription) {
        if (userRepository.findByUsername(deviceId).isPresent()) {
            throw new AuthenticationException("Device ID already exists");
        }

        User user = User.createKiosk(deviceId, passwordEncoder.encode(deviceSecret), locationDescription);
        return userRepository.save(user);
    }

    /**
     * Logout
     */
    public void logout(UUID sessionId) {
        sessionActivityService.invalidateSession(sessionId);
    }

    private LocalDate parseDateOfBirth(String dobString) {
        try {
            if (dobString.length() == 8 && !dobString.contains("-")) {
                return LocalDate.parse(dobString, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(dobString, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new AuthenticationException("Invalid date format. Use YYYYMMDD or YYYY-MM-DD");
        }
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}