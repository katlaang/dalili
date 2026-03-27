package dalili.com.base.application.controller;

import dalili.com.base.application.service.AdminPortalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/super")
public class AdminPortalController {

    private final AdminPortalService adminPortalService;

    public AdminPortalController(AdminPortalService adminPortalService) {
        this.adminPortalService = adminPortalService;
    }

    @GetMapping("/staff-accounts")
    public List<AdminPortalService.StaffAccountView> getStaffAccounts() {
        return adminPortalService.getNonPatientAccounts();
    }

    @GetMapping("/active-sessions")
    public List<AdminPortalService.ActiveSessionView> getActiveSessions() {
        return adminPortalService.getActiveNonPatientSessions();
    }

    @GetMapping("/audit-events")
    public List<AdminPortalService.AuditLogView> getAuditEvents(
            @RequestParam(defaultValue = "100") int limit
    ) {
        return adminPortalService.getRecentAuditEventsRedacted(limit);
    }
}

