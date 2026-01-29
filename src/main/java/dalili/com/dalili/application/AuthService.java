package dalili.com.dalili.application;


import dalili.com.dalili.domain.user.model.Role;
import dalili.com.dalili.domain.user.model.User;
import dalili.com.dalili.domain.user.repository.UserRepository;
import dalili.com.dalili.interfaces.security.jwt.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionActivityService sessionActivityService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SessionActivityService sessionActivityService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessionActivityService = sessionActivityService;
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

        JwtService.TokenResult result = jwtService.generateStaffToken(user.getId(), user.getUsername(), user.getRole());

        // Initialize session activity tracking
        sessionActivityService.touch(result.sessionId(), user.getId(), user.getActorType());

        return result.token();
    }

    /**
     * Patient login
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

        JwtService.TokenResult result = jwtService.generatePatientToken(user.getId(), user.getUsername(), user.getPatientId());

        // Initialize session activity tracking
        sessionActivityService.touch(result.sessionId(), user.getId(), user.getActorType());

        return result.token();
    }

    /**
     * Kiosk check-in - returns single-use token
     */
    public String kioskCheckIn(String kioskDeviceId, String mrn, String dobPin) {
        User kiosk = userRepository.findByUsernameAndActiveTrue(kioskDeviceId)
                .orElseThrow(() -> new AuthenticationException("Unknown kiosk device"));

        if (!kiosk.isKiosk()) {
            throw new AuthenticationException("Invalid device");
        }

        // TODO: Lookup patient by MRN and verify DOB
        UUID patientId = lookupPatientByMrnAndDob(mrn, dobPin);

        JwtService.TokenResult result = jwtService.generateKioskToken(kiosk.getId(), kioskDeviceId, patientId);

        // Create kiosk session (single-use tracking)
        sessionActivityService.createKioskSession(result.sessionId(), kiosk.getId(), patientId);

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
     * Register patient user
     */
    public User registerPatient(String username, String password, String fullName, UUID patientId) {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new AuthenticationException("Username already exists");
        }

        User user = User.createPatient(username, passwordEncoder.encode(password), fullName, patientId);
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
     * Logout - invalidate session
     */
    public void logout(UUID sessionId) {
        sessionActivityService.invalidateSession(sessionId);
    }

    private UUID lookupPatientByMrnAndDob(String mrn, String dobPin) {
        // TODO: Implement when Patient entity is ready
        throw new AuthenticationException("Patient lookup not yet implemented");
    }

    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }
}
