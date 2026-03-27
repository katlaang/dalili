package dalili.com.base.domain.user.model;

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

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(unique = true)
    private String email;

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
        String resolvedFirstName = resolveFirstName(fullName);
        String resolvedLastName = resolveLastName(fullName);
        return createStaff(username, passwordHash, resolvedFirstName, resolvedLastName, null, role);
    }

    public static User createStaff(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            Role role
    ) {
        if (role == Role.PATIENT || role == Role.KIOSK || role == Role.SYSTEM) {
            throw new IllegalArgumentException("Use appropriate factory method for non-staff users");
        }
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.email = email;
        user.fullName = buildFullName(firstName, lastName);
        user.role = role;
        user.actorType = ActorType.STAFF;
        user.patientId = null;
        return user;
    }

    /**
     * Create a patient user (linked to Patient record)
     */
    public static User createPatient(String username, String passwordHash, String fullName, UUID patientId) {
        String resolvedFirstName = resolveFirstName(fullName);
        String resolvedLastName = resolveLastName(fullName);
        return createPatient(username, passwordHash, resolvedFirstName, resolvedLastName, null, patientId);
    }

    public static User createPatient(
            String username,
            String passwordHash,
            String firstName,
            String lastName,
            String email,
            UUID patientId
    ) {
        if (patientId == null) {
            throw new IllegalArgumentException("Patient user must have patientId");
        }
        User user = new User();
        user.username = username;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.email = email;
        user.fullName = buildFullName(firstName, lastName);
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
        user.firstName = "SYSTEM";
        user.lastName = description;
        user.email = null;
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
        user.firstName = "KIOSK";
        user.lastName = locationDescription;
        user.email = null;
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

    private static String buildFullName(String firstName, String lastName) {
        if ((lastName == null || lastName.isBlank()) && firstName != null) {
            return firstName.trim();
        }
        if ((firstName == null || firstName.isBlank()) && lastName != null) {
            return lastName.trim();
        }
        if (firstName == null && lastName == null) {
            return "";
        }
        return (firstName == null ? "" : firstName.trim()) + " " + (lastName == null ? "" : lastName.trim());
    }

    private static String resolveFirstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String trimmed = fullName.trim();
        int splitIndex = trimmed.indexOf(' ');
        if (splitIndex < 0) {
            return trimmed;
        }
        return trimmed.substring(0, splitIndex).trim();
    }

    private static String resolveLastName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String trimmed = fullName.trim();
        int splitIndex = trimmed.indexOf(' ');
        if (splitIndex < 0) {
            return "";
        }
        return trimmed.substring(splitIndex + 1).trim();
    }
}
