package dalili.com.base.application.controller;

import dalili.com.base.application.service.AdminPortalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminUsersController {

    private final AdminPortalService adminPortalService;

    public AdminUsersController(AdminPortalService adminPortalService) {
        this.adminPortalService = adminPortalService;
    }

    @GetMapping("/users")
    public List<AdminPortalService.UserAccountView> getUsers() {
        return adminPortalService.getManagedUserAccounts();
    }
}
