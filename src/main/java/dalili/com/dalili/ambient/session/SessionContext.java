package dalili.com.dalili.ambient.session;

import dalili.com.dalili.domain.user.model.ActorType;
import dalili.com.dalili.domain.user.model.Role;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionContext {

    private static final ThreadLocal<UUID> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<Role> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<ActorType> ACTOR_TYPE = new ThreadLocal<>();
    private static final ThreadLocal<UUID> PATIENT_ID = new ThreadLocal<>();

    public void bind(UUID sessionId, UUID userId, String username, Role role, ActorType actorType, UUID patientId) {
        SESSION_ID.set(sessionId);
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLE.set(role);
        ACTOR_TYPE.set(actorType);
        PATIENT_ID.set(patientId);
    }

    public void clear() {
        SESSION_ID.remove();
        USER_ID.remove();
        USERNAME.remove();
        ROLE.remove();
        ACTOR_TYPE.remove();
        PATIENT_ID.remove();
    }

    public UUID sessionId() {
        return SESSION_ID.get();
    }

    public UUID userId() {
        return USER_ID.get();
    }

    public String username() {
        return USERNAME.get();
    }

    // Keep for backward compatibility with audit
    public String physicianId() {
        return USERNAME.get();
    }

    public Role role() {
        return ROLE.get();
    }

    public ActorType actorType() {
        return ACTOR_TYPE.get();
    }

    public UUID patientId() {
        return PATIENT_ID.get();
    }

    public boolean isActive() {
        return SESSION_ID.get() != null && USER_ID.get() != null;
    }

    public boolean isStaff() {
        return ACTOR_TYPE.get() == ActorType.STAFF;
    }

    public boolean isPatient() {
        return ACTOR_TYPE.get() == ActorType.PATIENT;
    }

    public boolean isKiosk() {
        return ACTOR_TYPE.get() == ActorType.KIOSK;
    }

    public boolean isSystem() {
        return ACTOR_TYPE.get() == ActorType.SYSTEM;
    }
}