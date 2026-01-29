package dalili.com.dalili.domain.user.model;

/**
 * Distinguishes human users from system processes and devices.
 * Critical for audit interpretation.
 */
public enum ActorType {
    STAFF,      // Clinicians, admins, pharmacists
    PATIENT,    // Patient portal/app users
    KIOSK,      // Self-service terminals
    SYSTEM      // Background jobs, sync, anchoring
}
