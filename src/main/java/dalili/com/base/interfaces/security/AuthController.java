package dalili.com.base.interfaces.security;

import dalili.com.base.ambient.session.SessionContext;
import dalili.com.base.application.service.AuthService;
import dalili.com.base.domain.patient.model.Patient;
import dalili.com.base.domain.user.model.Role;
import dalili.com.base.domain.user.model.User;
import dalili.com.base.domain.user.repository.UserRepository;
import dalili.com.base.interfaces.security.jwt.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final SessionContext sessionContext;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, SessionContext sessionContext, JwtService jwtService, UserRepository userRepository) {
        this.authService = authService;
        this.sessionContext = sessionContext;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    // ==================== LOGIN ====================

    @PostMapping("/staff/login")
    public ResponseEntity<LoginResponse> loginStaff(@RequestBody LoginRequest request) {
        try {
            String token = authService.loginStaff(request.username(), request.password());
            String role = jwtService.getRole(token).name();
            log.info("Staff login success username={} role={}", request.username(), role);
            return ResponseEntity.ok(new LoginResponse(token, "Login successful", role));
        } catch (AuthService.AuthenticationException e) {
            log.warn("Staff login failed username={} reason={}", request.username(), e.getMessage());
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage(), null));
        }
    }

    @PostMapping("/patient/login")
    public ResponseEntity<LoginResponse> loginPatient(@RequestBody LoginRequest request) {
        try {
            String token = authService.loginPatient(request.username(), request.password());
            log.info("Patient login success username={}", request.username());
            return ResponseEntity.ok(new LoginResponse(token, "Login successful", Role.PATIENT.name()));
        } catch (AuthService.AuthenticationException e) {
            log.warn("Patient login failed username={} reason={}", request.username(), e.getMessage());
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage(), null));
        }
    }

    @PostMapping("/kiosk/login")
    public ResponseEntity<LoginResponse> loginKioskDevice(@RequestBody KioskLoginRequest request) {
        try {
            String token = authService.loginKioskDevice(request.deviceId(), request.deviceSecret());
            log.info("Kiosk device login success deviceId={}", request.deviceId());
            return ResponseEntity.ok(new LoginResponse(token, "Login successful", Role.KIOSK.name()));
        } catch (AuthService.AuthenticationException e) {
            log.warn("Kiosk device login failed deviceId={} reason={}", request.deviceId(), e.getMessage());
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage(), null));
        }
    }

    @PostMapping("/kiosk/checkin")
    public ResponseEntity<LoginResponse> kioskCheckIn(@RequestBody KioskCheckInRequest request) {
        try {
            String token = authService.kioskCheckIn(
                    request.kioskDeviceId(),
                    request.mrn(),
                    request.dateOfBirth()
            );
            log.info("Kiosk check-in success kioskDeviceId={}", request.kioskDeviceId());
            return ResponseEntity.ok(new LoginResponse(token, "Check-in successful", Role.KIOSK.name()));
        } catch (AuthService.AuthenticationException e) {
            log.warn("Kiosk check-in failed kioskDeviceId={} reason={}", request.kioskDeviceId(), e.getMessage());
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage(), null));
        }
    }

    @PostMapping("/kiosk/identify")
    public ResponseEntity<LoginResponse> kioskIdentify(@RequestBody KioskIdentifyRequest request) {
        try {
            String token = authService.kioskIdentifyByName(
                    request.kioskDeviceId(),
                    request.givenName(),
                    request.familyName(),
                    request.dateOfBirth(),
                    request.sex()
            );
            log.info("Kiosk identify success kioskDeviceId={}", request.kioskDeviceId());
            return ResponseEntity.ok(new LoginResponse(token, "Check-in successful", Role.KIOSK.name()));
        } catch (AuthService.AuthenticationException e) {
            log.warn("Kiosk identify failed kioskDeviceId={} reason={}", request.kioskDeviceId(), e.getMessage());
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage(), null));
        }
    }

    // ==================== LOGOUT ====================

    @GetMapping("/super-admin/bootstrap-status")
    public ResponseEntity<BootstrapStatusResponse> getSuperAdminBootstrapStatus() {
        return ResponseEntity.ok(new BootstrapStatusResponse(authService.isSuperAdminBootstrapAllowed()));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentProfile() {
        UUID userId = sessionContext.userId();
        if (userId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("No active session"));
        }

        return userRepository.findById(userId)
                .filter(User::isActive)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(new ProfileResponse(
                        user.getId().toString(),
                        user.getUsername(),
                        user.getFullName(),
                        user.getRole().name()
                )))
                .orElseGet(() -> ResponseEntity.status(401).body(new ErrorResponse("Session user not found")));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout() {
        UUID sessionId = sessionContext.sessionId();
        if (sessionId != null) {
            authService.logout(sessionId);
        }
        return ResponseEntity.ok(new MessageResponse("Logged out successfully"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest request) {
        UUID userId = sessionContext.userId();
        if (userId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("No active session"));
        }

        try {
            authService.changePassword(userId, request.currentPassword(), request.newPassword());
            return ResponseEntity.ok(new MessageResponse("Password changed successfully"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    // ==================== REGISTRATION ====================

    @PostMapping("/staff/register")
    public ResponseEntity<RegisterResponse> registerStaff(@RequestBody StaffRegisterRequest request) {
        try {
            User user = authService.registerStaff(
                    request.username(),
                    request.password(),
                    request.firstName(),
                    request.lastName(),
                    request.email(),
                    request.role()
            );
            return ResponseEntity.ok(new RegisterResponse(user.getId().toString(), "Staff registered"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new RegisterResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/patient/register")
    public ResponseEntity<RegisterResponse> registerPatientUser(@RequestBody PatientUserRegisterRequest request) {
        try {
            User user = authService.registerPatientUser(
                    request.username(),
                    request.password(),
                    request.patientId()
            );
            return ResponseEntity.ok(new RegisterResponse(user.getId().toString(), "Patient user registered"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new RegisterResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/kiosk/register")
    public ResponseEntity<RegisterResponse> registerKiosk(@RequestBody KioskRegisterRequest request) {
        try {
            User user = authService.registerKiosk(
                    request.deviceId(),
                    request.deviceSecret(),
                    request.locationDescription()
            );
            return ResponseEntity.ok(new RegisterResponse(user.getId().toString(), "Kiosk registered"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new RegisterResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/super-admin/bootstrap")
    public ResponseEntity<AccountRegistrationResponse> bootstrapSuperAdmin(
            @RequestBody SuperAdminBootstrapRequest request
    ) {
        try {
            User user = authService.bootstrapFirstSuperAdmin(
                    request.username(),
                    request.firstName(),
                    request.lastName(),
                    request.email(),
                    request.password(),
                    request.company()
            );
            return ResponseEntity.ok(
                    new AccountRegistrationResponse(user.getId().toString(), user.getUsername(), "Super admin created")
            );
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new AccountRegistrationResponse(null, null, e.getMessage()));
        }
    }

    @PostMapping("/admin/register")
    public ResponseEntity<AccountRegistrationResponse> registerAdmin(@RequestBody AdminRegisterRequest request) {
        try {
            User user = authService.registerAdmin(
                    request.username(),
                    request.firstName(),
                    request.lastName(),
                    request.email(),
                    request.password(),
                    request.company()
            );
            return ResponseEntity.ok(
                    new AccountRegistrationResponse(user.getId().toString(), user.getUsername(), "Admin registered")
            );
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new AccountRegistrationResponse(null, null, e.getMessage()));
        }
    }

    // ==================== DTOs ====================

    record LoginRequest(String username, String password) {
    }

    record LoginResponse(String token, String message, String role) {
    }

    record MessageResponse(String message) {
    }

    record ErrorResponse(String error) {
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    record ProfileResponse(String userId, String username, String fullName, String role) {
    }

    record BootstrapStatusResponse(boolean bootstrapAllowed) {
    }

    record KioskCheckInRequest(String kioskDeviceId, String mrn, String dateOfBirth) {
    }

    record KioskLoginRequest(String deviceId, String deviceSecret) {
    }

    record KioskIdentifyRequest(
            String kioskDeviceId,
            String givenName,
            String familyName,
            String dateOfBirth,
            Patient.Sex sex
    ) {
    }

    record StaffRegisterRequest(
            String username,
            String password,
            String firstName,
            String lastName,
            String email,
            Role role
    ) {
    }

    record PatientUserRegisterRequest(String username, String password, UUID patientId) {
    }

    record KioskRegisterRequest(String deviceId, String deviceSecret, String locationDescription) {
    }

    record SuperAdminBootstrapRequest(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String company
    ) {
    }

    record AdminRegisterRequest(
            String username,
            String firstName,
            String lastName,
            String email,
            String password,
            String company
    ) {
    }

    record AccountRegistrationResponse(String userId, String username, String message) {
    }

    record RegisterResponse(String userId, String message) {
    }
}
