package dalili.com.dalili.ambient.session;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SessionContext {

    private static final ThreadLocal<UUID> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> PHYSICIAN_ID = new ThreadLocal<>();

    public void bind(UUID sessionId, String physicianId) {
        SESSION_ID.set(sessionId);
        PHYSICIAN_ID.set(physicianId);
    }

    public void clear() {
        SESSION_ID.remove();
        PHYSICIAN_ID.remove();
    }

    public UUID sessionId() {
        return SESSION_ID.get();
    }

    public String physicianId() {
        return PHYSICIAN_ID.get();
    }

    public boolean isActive() {
        return SESSION_ID.get() != null && PHYSICIAN_ID.get() != null;
    }
}
