package dalili.com.dalili.domain.user.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    @Setter
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActorType actorType;

    /**
     * Links to Patient record for PATIENT users.
     * Must be null for STAFF/SYSTEM users.
     */
    @Column(name = "patient_id")
    private UUID patientId;

    @Column(nullable = false)
    private boolean active = true;

    protected User() {
    }

    /**
     * Create a staff user (no patient link)
     */
    public static User createStaff(String username, String passwordHash, String fullName, Role role) {
        if (role == Role.PATIENT || role == Role.KIOSK || role == Role.SYSTEM) {
            throw new IllegalArgumentException("Use appropriate factory method for non-staff users");
        }
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = role;
        user.actorType = ActorType.STAFF;
        user.patientId = null;
        return user;
    }

    /**
     * Create a patient user (linked to Patient record)
     */
    public static User createPatient(String username, String passwordHash, String fullName, UUID patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("Patient user must have patientId");
        }
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.role = Role.PATIENT;
        user.actorType = ActorType.PATIENT;
        user.patientId = patientId;
        return user;
    }

    /**
     * Create a system user (background jobs, sync)
     */
    public static User createSystem(String username, String passwordHash, String description) {
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        user.fullName = description;
        user.role = Role.SYSTEM;
        user.actorType = ActorType.SYSTEM;
        user.patientId = null;
        return user;
    }

    /**
     * Create a kiosk device identity
     */
    public static User createKiosk(String deviceId, String passwordHash, String locationDescription) {
        User user = new User();
        user.username = deviceId;
        user.passwordHash = passwordHash;
        user.fullName = locationDescription;
        user.role = Role.KIOSK;
        user.actorType = ActorType.KIOSK;
        user.patientId = null;
        return user;
    }

    public boolean isStaff() {
        return actorType == ActorType.STAFF;
    }

    public boolean isPatient() {
        return actorType == ActorType.PATIENT;
    }

    public boolean isKiosk() {
        return actorType == ActorType.KIOSK;
    }

    public boolean isSystem() {
        return actorType == ActorType.SYSTEM;
    }
}
