package dalili.com.dalili.interfaces.security;

import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class DefaultAuditAuthorizationService
        implements AuditAuthorizationService {

    // Example: hardcoded for now
    private static final Set<String> AUTHORIZED_PHYSICIANS =
            Set.of("AUDITOR-001", "ADMIN-001");

    @Override
    public boolean canExportAudit(String physicianId) {
        return AUTHORIZED_PHYSICIANS.contains(physicianId);
    }
}

