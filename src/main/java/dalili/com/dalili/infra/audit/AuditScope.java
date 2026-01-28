package dalili.com.dalili.infra.audit;

public final class AuditScope {

    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> false);

    private AuditScope() {
    }

    public static void enter() {
        ACTIVE.set(true);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}

