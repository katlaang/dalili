package dalili.com.dalili.interfaces.audit;

import dalili.com.dalili.infra.audit.sync.AuditSyncPayload;
import dalili.com.dalili.infra.audit.sync.AuditSyncService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit/sync")
public class AuditSyncController {

    private final AuditSyncService syncService;

    public AuditSyncController(AuditSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/prepare")
    public AuditSyncPayload prepare(
            @RequestParam(required = false) String lastHash
    ) {
        return syncService.preparePayload(lastHash);
    }

    @PostMapping("/apply")
    public void apply(@RequestBody AuditSyncPayload payload) {
        syncService.applyPayload(payload);
    }
}

