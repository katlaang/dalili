package dalili.com.dalili.interfaces.security;


public class AuditBypassException extends RuntimeException {

    public AuditBypassException(String message) {
        super(message);
    }
}
