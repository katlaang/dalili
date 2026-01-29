package dalili.com.dalili.interfaces.security;

import dalili.com.dalili.ambient.session.SessionContext;
import dalili.com.dalili.application.AuthService;
import dalili.com.dalili.domain.user.model.Role;
import dalili.com.dalili.domain.user.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionContext sessionContext;

    public AuthController(AuthService authService, SessionContext sessionContext) {
        this.authService = authService;
        this.sessionContext = sessionContext;
    }

    // ==================== LOGIN ====================

    @PostMapping("/staff/login")
    public ResponseEntity<LoginResponse> loginStaff(@RequestBody LoginRequest request) {
        try {
            String token = authService.loginStaff(request.username(), request.password());
            return ResponseEntity.ok(new LoginResponse(token, "Login successful"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/patient/login")
    public ResponseEntity<LoginResponse> loginPatient(@RequestBody LoginRequest request) {
        try {
            String token = authService.loginPatient(request.username(), request.password());
            return ResponseEntity.ok(new LoginResponse(token, "Login successful"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/kiosk/checkin")
    public ResponseEntity<LoginResponse> kioskCheckIn(@RequestBody KioskCheckInRequest request) {
        try {
            String token = authService.kioskCheckIn(request.kioskDeviceId(), request.mrn(), request.dobPin());
            return ResponseEntity.ok(new LoginResponse(token, "Check-in successful"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.status(401).body(new LoginResponse(null, e.getMessage()));
        }
    }

    // ==================== LOGOUT ====================

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout() {
        UUID sessionId = sessionContext.sessionId();
        if (sessionId != null) {
            authService.logout(sessionId);
        }
        return ResponseEntity.ok(new LogoutResponse("Logged out successfully"));
    }

    // ==================== REGISTRATION ====================

    @PostMapping("/staff/register")
    public ResponseEntity<RegisterResponse> registerStaff(@RequestBody StaffRegisterRequest request) {
        try {
            User user = authService.registerStaff(
                    request.username(),
                    request.password(),
                    request.fullName(),
                    request.role()
            );
            return ResponseEntity.ok(new RegisterResponse(user.getId().toString(), "Staff registered"));
        } catch (AuthService.AuthenticationException e) {
            return ResponseEntity.badRequest().body(new RegisterResponse(null, e.getMessage()));
        }
    }

    @PostMapping("/patient/register")
    public ResponseEntity<RegisterResponse> registerPatient(@RequestBody PatientRegisterRequest request) {
        try {
            User user = authService.registerPatient(
                    request.username(),
                    request.password(),
                    request.fullName(),
                    request.patientId()
            );
            return ResponseEntity.ok(new RegisterResponse(user.getId().toString(), "Patient registered"));
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


    record LoginRequest(String username, String password) {
    }

    record LoginResponse(String token, String message) {
    }

    record LogoutResponse(String message) {
    }

    record KioskCheckInRequest(String kioskDeviceId, String mrn, String dobPin) {
    }

    record StaffRegisterRequest(String username, String password, String fullName, Role role) {
    }

    record PatientRegisterRequest(String username, String password, String fullName, UUID patientId) {
    }

    record KioskRegisterRequest(String deviceId, String deviceSecret, String locationDescription) {
    }

    record RegisterResponse(String userId, String message) {
    }
}
